package bank.approvals.app

import arrow.core.Either
import bank.approvals.domain.Refusal
import bank.approvals.domain.Request
import bank.approvals.domain.RequestCommand
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.approvals.domain.decide
import bank.approvals.domain.evolve
import bank.approvals.protocol.AskOwnerAgain
import bank.approvals.protocol.ChainedEvent
import bank.approvals.protocol.chained
import bank.approvals.protocol.ExpiryDue
import bank.approvals.protocol.Kinds
import bank.approvals.protocol.Release
import bank.approvals.protocol.RequestAnswer
import bank.approvals.protocol.RequestAsk
import bank.approvals.protocol.RequestEvents
import bank.approvals.protocol.RequestMessage
import bank.approvals.protocol.SubjectMessage
import bank.approvals.protocol.Wake
import bank.approvals.protocol.command
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Ctx
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Remembered
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logWarn
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A request as it stands, every event it took to get there (what a reader is answered with), and the link of the last
 * in its hash chain, which the next event's link follows from.
 */
data class Tracked(val request: Request, val history: List<RequestEvent>, val head: String?) {
    fun evolve(chained: ChainedEvent) = Tracked(evolve(request, chained.event), history + chained.event, chained.link)

    companion object {
        val EMPTY = Tracked(Request.Unasked, emptyList(), null)
    }
}

/**
 * The owning service, asked again to apply an approved change. The first asking is the `ApprovalGiven` or
 * `AutoApproved` event itself, published with every other (Publishing.kt); this is each `applyEvery` after it until the
 * owner says it applied it, so an owner that missed the event, or was down for it, still hears. It applies a request
 * id once.
 */
fun interface Owners {
    fun askAgain(id: RequestId, history: List<RequestEvent>)
}

/** The time a request stamps its events with: lark's clock, so a test can hold it still. */
internal fun now(): Instant = clock.get().now()

private const val EXPIRE = "expire"
private const val APPLY = "apply"

/**
 * One request, as a saga (lark-bank spec 0019): each act decided by the pure domain, written to the journal, and
 * answered with the whole history once written. The next step is read off the state, so a request recovered on another
 * node carries on: its expiry is armed again, and so is an approved one's asking its owner again.
 */
fun request(
    id: RequestId,
    subjects: (String) -> ActorRef<SubjectMessage>,
    owners: Owners,
    applyEvery: Duration,
): Behaviour<RequestMessage, Remembered<Tracked>, JournalConflict> {

    /** Arms what the state needs next; [asking] is the apply timer firing, the one time the owner is asked again. */
    fun Ctx<RequestMessage>.drive(tracked: Tracked, asking: Boolean = false) {
        when (val request = tracked.request) {
            Request.Unasked -> Unit
            is Request.Waiting -> {
                val left = request.asked.expiresAt.toEpochMilli() - now().toEpochMilli()
                timers.after(EXPIRE, left.coerceAtLeast(0).milliseconds, ExpiryDue)
            }
            is Request.Agreed -> {
                timers.cancel(EXPIRE)
                timers.after(APPLY, applyEvery, AskOwnerAgain)
                if (asking) {
                    try {
                        owners.askAgain(id, tracked.history)
                        counter("approvals.apply_asked").increment()
                    } catch (failed: RuntimeException) {
                        // Asked again when the timer next fires; an owner out of reach is not the request's failure.
                        logWarn("request ${id.value}: its owner could not be asked to apply it: ${failed.message}")
                    }
                }
            }
            is Request.Ended -> {
                timers.cancel(EXPIRE)
                timers.cancel(APPLY)
                // Sent on every drive that finds it ended, a recovery's included; a second release changes nothing.
                subjects(request.asked.proposal.subject).tell(Release(id))
            }
        }
    }

    return persistent<RequestMessage, ChainedEvent, Tracked>(
        id = PersistenceId(Kinds.REQUEST, id.value),
        empty = Tracked.EMPTY,
        codec = RequestEvents,
        command = { ctx, state, message ->
            when (message) {
                is RequestAsk -> when (val command = message.act.command(id, now())) {
                    // A read: the history as it stands, or that nothing was ever asked under this id.
                    null -> none().then { after ->
                        val answer: RequestAnswer =
                            if (after.request == Request.Unasked) Either.Left(Refusal.NotAsked)
                            else Either.Right(after.history)
                        message.reply(answer)
                    }
                    else -> decide(state.request, command).fold(
                        { refusal -> none().then { _ -> message.reply(Either.Left(refusal)) } },
                        { events ->
                            if (events.isEmpty()) {
                                none().then { after -> message.reply(Either.Right(after.history)) }
                            } else {
                                events.forEach { counter("approvals.events", "event" to it::class.simpleName.orEmpty()).increment() }
                                persist(*chained(state.head, events).toTypedArray()).then { after ->
                                    message.reply(Either.Right(after.history))
                                    ctx.drive(after)
                                }
                            }
                        },
                    )
                }
                Wake -> none().then { after -> ctx.drive(after) }
                AskOwnerAgain -> none().then { after -> ctx.drive(after, asking = true) }
                // Due, if it is still waiting; a timer that outlived its reason finds nothing to do.
                ExpiryDue -> decide(state.request, RequestCommand.Expire(now())).fold(
                    { none().then { after -> ctx.drive(after) } },
                    { events ->
                        if (events.isEmpty()) none()
                        else persist(*chained(state.head, events).toTypedArray()).then { after -> ctx.drive(after) }
                    },
                )
            }
        },
        event = Tracked::evolve,
    ).onStart { ctx -> ctx.self.tell(Wake) }
}
