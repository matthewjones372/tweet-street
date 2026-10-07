package bank.app

import arrow.core.Either
import bank.domain.Account
import bank.domain.AccountCommand
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.balanceOf
import bank.domain.decide
import bank.domain.evolve
import bank.protocol.AccountAsk
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.BulkCreditSent
import bank.protocol.Kinds
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Remembered
import io.github.matthewjones372.lark.actor.Snapshotting
import io.github.matthewjones372.lark.actor.delivered
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import io.github.matthewjones372.lark.otel.tracedSpan

/** The time an event is stamped with: lark's clock, so a test can hold it still. */
internal fun now(): Long = clock.get().now().toEpochMilli()

/**
 * One account: the only writer of its balance, wherever in the cluster its shard lives. Each command is decided by
 * the pure domain, written to the journal, and answered once written; the commands waiting for a busy account are
 * written a batch to an append. A reliably delivered credit is confirmed by
 * `delivered` after its step, and dropped unrun if its delivery was already handled.
 */
fun account(
    id: AccountId,
    snapshots: Snapshotting<Account>?,
    batch: Int = 1,
    tracer: Tracer = OpenTelemetry.noop().getTracer("lark-bank"),
): Behaviour<AccountMessage, Remembered<Account>, JournalConflict> =
    delivered(
        persistent<AccountMessage, AccountEvent, Account>(
            id = PersistenceId(Kinds.ACCOUNT, id.value),
            empty = Account.Unopened,
            codec = AccountEvents,
            command = { _, state, message ->
                when (message) {
                    is AccountAsk -> {
                        val command = message.command
                        if (command == null) {
                            none().then { message.reply(state.balanceOf(id)) }
                        } else tracer.tracedSpan("account ${command::class.simpleName?.lowercase()}") {
                            // A span on the account's node, in the trace the command arrived in (bank spec 0024).
                            state.decide(id, command, now()).fold(
                                { refusal ->
                                    counted("refused")
                                    none().then { message.reply(Either.Left(refusal)) }
                                },
                                { events ->
                                    counted(if (events.isEmpty()) "repeated" else "applied")
                                    persist(*events.toTypedArray()).then { after -> message.reply(after.balanceOf(id)) }
                                },
                            )
                        }
                    }
                    is BulkCreditSent ->
                        state.decide(id, AccountCommand.Deposit(message.credit.amount, message.credit.reference), now())
                            .fold(
                                { counted("refused"); none() },
                                { events -> counted("bulk"); persist(*events.toTypedArray()) },
                            )
                }
            },
            event = Account::evolve,
            snapshots = snapshots,
            // A busy account decides the commands waiting for it and writes them in one append (lark spec 0085).
            batch = batch,
        ),
    )

/** One tag, one key, always the same set, or Prometheus drops the series. */
private fun counted(outcome: String) = counter("bank.account.commands", "outcome" to outcome).increment()
