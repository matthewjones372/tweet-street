package bank.app

import arrow.core.Either
import bank.domain.AccountCommand
import bank.domain.AccountId
import bank.domain.TransferError
import bank.domain.Step
import bank.domain.Transfer
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.after
import bank.domain.evolve
import bank.domain.next
import bank.domain.screened
import bank.domain.unanswered
import bank.domain.view
import bank.protocol.AccountAsk
import bank.protocol.AccountAnswer
import bank.protocol.AccountMessage
import bank.protocol.Kinds
import bank.protocol.LegAnswered
import bank.protocol.LegTimedOut
import bank.protocol.Nudge
import bank.protocol.ScreeningAnswered
import bank.protocol.ScreeningUnanswered
import bank.protocol.TransferAsk
import bank.protocol.TransferEvents
import bank.protocol.TransferAnswer
import bank.protocol.TransferMessage
import bank.protocol.TransferRequest
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Ctx
import io.github.matthewjones372.lark.actor.Effects
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Remembered
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.logDebug
import io.github.matthewjones372.lark.increment
import kotlin.time.Duration
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import io.github.matthewjones372.lark.otel.tracedSpan
import io.github.matthewjones372.lark.Carriers

/** Where an account entity is, by id, on whichever node asks. */
fun interface AccountsById {
    fun entity(id: String): ActorRef<AccountMessage>
}

/**
 * An account's answer to one leg, turned into a message to the saga itself. It is registered as a pending reply when
 * it crosses to another node, and invoked there when the answer comes back.
 */
private class LegReply(private val saga: ActorRef<TransferMessage>, private val step: Step) : Reply<AccountAnswer> {
    override val address: Address get() = saga.address

    override fun invoke(answer: AccountAnswer) = saga.tell(LegAnswered(step, answer))
}

/** Where an account's answer to a close goes: nowhere, since a close lost only leaves a leg open (spec 0013). */
private class Unheeded(private val saga: ActorRef<TransferMessage>) : Reply<AccountAnswer> {
    override val address: Address get() = saga.address

    override fun invoke(answer: AccountAnswer) = Unit
}

private const val LEG = "leg"
private const val SCREEN = "screen"

/**
 * One transfer, as a saga (spec 0001): each step is an event, and the next step is read off the state, so a saga
 * recovered on another node carries on from where its last event left it. The replies waiting for it to settle are
 * this incarnation's only: after a move they are gone, and the caller's own timeout answers it as still pending.
 */
fun transfer(
    id: TransferId,
    accounts: AccountsById,
    legTimeout: Duration,
    screening: Screening = Screening.Off,
    tracer: Tracer = OpenTelemetry.noop().getTracer("lark-bank"),
): Behaviour<TransferMessage, Remembered<Transfer>, JournalConflict> {
    val waiting = ArrayList<Reply<TransferAnswer>>()
    val screens = screening is Screening.On
    // A new saga is driven twice at once, by its start and by the nudge it sends itself on starting: harmless for a
    // leg, which is idempotent, but one call to the check each is enough. The timer always ends the wait.
    var asking = false

    /** The check asked, and its answer, or its silence, told to [saga]. */
    fun screened(on: Screening.On, step: Step.Screen, saga: ActorRef<TransferMessage>) =
        on.screener.screen(id, step).fold(
            { none ->
                logDebug("transfer ${id.value}: screening gave no decision: ${none.why}")
                saga.tell(ScreeningUnanswered)
            },
            { decision -> saga.tell(ScreeningAnswered(decision)) },
        )

    // The check is asked on a virtual thread, and its answer comes back as a message, as an account's does. The timer
    // decides for it if it is slow; a failure decides at once. A saga that moves asks again, and gets the same answer.
    fun Ctx<TransferMessage>.screen(on: Screening.On, step: Step.Screen) {
        if (asking) return
        asking = true
        timers.after(SCREEN, on.timeout, ScreeningUnanswered)
        val saga = self
        // A thread of its own takes nothing from this one: what the saga carries is handed over, so the call to the
        // check is in the transfer's trace (bank spec 0024).
        val carried = Carriers.capture()
        Thread.ofVirtual().name("screen-${id.value}").start {
            Carriers.within(carried) { screened(on, step, saga) }
        }
    }

    fun Ctx<TransferMessage>.sendLeg(step: Step, account: AccountId, command: AccountCommand) {
        // The timer first: a leg that cannot be sent now is sent again when it fires, as one whose answer was lost is.
        timers.after(LEG, legTimeout, LegTimedOut(step))
        try {
            // Sent inside a span of its own, so the account's handling of it is that span's child (bank spec 0024).
            tracer.tracedSpan("transfer ${step::class.simpleName?.lowercase()}") {
                accounts.entity(account.value).tell(AccountAsk(command, LegReply(self, step)))
            }
        } catch (full: IllegalStateException) {
            // A tell from a step throws when the region's mailbox is full, as it is when a node rejoins and every saga
            // it recovers sends a leg at once. Failing the step would stop the saga with its money in flight.
            counter("bank.transfer.legs_deferred").increment()
            logDebug("transfer ${id.value}: ${full.message}; the leg goes again in $legTimeout")
        }
    }

    // Settled, no leg of this transfer can be sent again: each account may forget the legs it applied (spec 0013).
    // Sent on every drive that finds the saga settled, a recovery's included; a second close writes nothing.
    fun Ctx<TransferMessage>.close(moving: Transfer.Moving) = listOf(moving.from, moving.to).forEach { account ->
        try {
            accounts.entity(account.value).tell(AccountAsk(AccountCommand.Close(id), Unheeded(self)))
        } catch (full: IllegalStateException) {
            logDebug("transfer ${id.value}: its close to $account was not sent: ${full.message}")
        }
    }

    fun Ctx<TransferMessage>.drive(state: Transfer) {
        when (val step = state.next(id, screens)) {
            is Step.Screen -> when (screening) {
                is Screening.On -> screen(screening, step)
                // Screening off never asks for this step.
                Screening.Off -> Unit
            }
            Step.Done -> {
                if (state is Transfer.Moving) {
                    val answer: TransferAnswer = Either.Right(state.view(id))
                    waiting.forEach { it(answer) }
                    waiting.clear()
                    close(state)
                }
                timers.cancel(LEG)
            }
            is Step.DebitSource -> sendLeg(step, step.account, step.command)
            is Step.CreditDestination -> sendLeg(step, step.account, step.command)
            is Step.RefundSource -> sendLeg(step, step.account, step.command)
        }
    }

    return persistent<TransferMessage, TransferEvent, Transfer>(
        id = PersistenceId(Kinds.TRANSFER, id.value),
        empty = Transfer.Unrequested,
        codec = TransferEvents,
        command = { ctx, state, message ->
            when (message) {
                is TransferAsk -> asked(id, state, message, waiting) { after -> ctx.drive(after) }
                Nudge -> none().then { after -> ctx.drive(after) }
                is LegAnswered -> {
                    val moving = state as? Transfer.Moving
                    // An answer to a leg the saga has moved past: a retry's twin, arriving second.
                    if (moving == null || moving.next(id, screens) != message.step) {
                        none()
                    } else {
                        val refusal = message.answer.leftOrNull()
                        when (val event = moving.after(message.step, refusal, now())) {
                            null -> none()
                            else -> {
                                counter("bank.transfer.steps", "event" to event::class.simpleName.orEmpty()).increment()
                                persist(event).then { after -> ctx.drive(after) }
                            }
                        }
                    }
                }
                is LegTimedOut ->
                    if (state.next(id, screens) == message.step) none().then { after -> ctx.drive(after) } else none()
                // Either is for a saga still waiting on screening; a second, or one after a move, is ignored.
                is ScreeningAnswered ->
                    if (state.next(id, screens) !is Step.Screen) none().also { asking = false }
                    else screened(message.decision.screened(now())) { after ->
                        asking = false
                        ctx.timers.cancel(SCREEN)
                        ctx.drive(after)
                    }
                ScreeningUnanswered -> when (screening) {
                    is Screening.On ->
                        if (state.next(id, screens) !is Step.Screen) none().also { asking = false }
                        else screened(unanswered(screening.whenUnanswered, now())) { after ->
                            asking = false
                            counter("bank.transfer.screened_unanswered").increment()
                            ctx.timers.cancel(SCREEN)
                            ctx.drive(after)
                        }
                    Screening.Off -> none()
                }
            }
        },
        event = Transfer::evolve,
    ).onStart { ctx -> ctx.self.tell(Nudge) }
}

/** Screening's decision, or the bank's for it, written down; then the saga carries on from it. */
private fun Effects<TransferEvent, Transfer>.screened(event: TransferEvent.Screened, then: (Transfer) -> Unit) = run {
    counter("bank.transfer.steps", "event" to "Screened").increment()
    persist(event).then(then)
}

/** A start, or a question about how far it has got. The same start twice is one transfer. */
private fun Effects<TransferEvent, Transfer>.asked(
    id: TransferId,
    state: Transfer,
    message: TransferAsk,
    waiting: MutableList<Reply<TransferAnswer>>,
    drive: (Transfer) -> Unit,
) = when (val request = message.request) {
    is TransferRequest.Start ->
        when (state) {
            Transfer.Unrequested ->
                persist(TransferEvent.Requested(request.from, request.to, request.amount, now()))
                    .then { after ->
                        waiting += message.reply
                        drive(after)
                    }
            is Transfer.Moving ->
                if (state.from != request.from || state.to != request.to || state.amount != request.amount) {
                    none().then { message.reply(Either.Left(TransferError.TransferIdReused(id.value))) }
                } else if (state.settled) {
                    none().then { message.reply(Either.Right(state.view(id))) }
                } else {
                    none().then { waiting += message.reply }
                }
        }
    TransferRequest.Status ->
        none().then {
            message.reply(
                when (state) {
                    Transfer.Unrequested -> Either.Left(TransferError.NoSuchTransfer(id.value))
                    is Transfer.Moving -> Either.Right(state.view(id))
                },
            )
        }
}
