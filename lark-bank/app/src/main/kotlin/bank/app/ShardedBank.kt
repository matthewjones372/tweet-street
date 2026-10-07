package bank.app

import arrow.core.Either
import arrow.core.left
import bank.domain.AccountCommand
import bank.domain.AccountError
import bank.domain.AccountId
import bank.domain.Balance
import bank.domain.Bank
import bank.domain.Money
import bank.domain.TransferError
import bank.domain.TransferId
import bank.domain.TransferView
import bank.domain.Unavailable
import bank.protocol.AccountAnswer
import bank.protocol.AccountAsk
import bank.protocol.AccountMessage
import bank.protocol.TransferAnswer
import bank.protocol.TransferAsk
import bank.protocol.TransferMessage
import bank.protocol.TransferRequest
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.AskFailure
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.timed
import kotlin.time.Duration

/**
 * The bank as sharded entities: each call is an ask to the one entity that owns the id, on whichever node that is.
 * An ask that is not answered in time is [Unavailable] — the caller retries with the same reference, and the
 * entity's idempotency makes the retry safe.
 */
class ShardedBank(
    private val accounts: (String) -> ActorRef<AccountMessage>,
    private val transfers: (String) -> ActorRef<TransferMessage>,
    private val askTimeout: Duration,
    private val transferWait: Duration,
) : Bank {

    override fun open(id: AccountId, owner: String, initial: Money, reference: String) =
        command(id, AccountCommand.Open(owner, initial, reference))

    override fun deposit(id: AccountId, amount: Money, reference: String) =
        command(id, AccountCommand.Deposit(amount, reference))

    override fun withdraw(id: AccountId, amount: Money, reference: String) =
        command(id, AccountCommand.Withdraw(amount, reference))

    override fun balance(id: AccountId): Either<AccountError, Balance> = timed("bank.ask.duration", "entity" to "account") {
        accounts(id.value).ask<AccountMessage, AccountAnswer>(askTimeout) { AccountAsk(null, it) }
            .fold({ failure -> unavailable("account $id", failure).left() }, { it })
    }

    private fun command(id: AccountId, command: AccountCommand): Either<AccountError, Balance> =
        timed("bank.ask.duration", "entity" to "account") {
            accounts(id.value).ask<AccountMessage, AccountAnswer>(askTimeout) { AccountAsk(command, it) }
                .fold({ failure -> unavailable("account $id", failure).left() }, { it })
        }

    /**
     * Waits [transferWait] for the saga to settle. One that has not is asked where it has got to, and answered as
     * pending, debited or refunding: still moving, and safe to ask about again.
     */
    override fun transfer(id: TransferId, from: AccountId, to: AccountId, amount: Money): Either<TransferError, TransferView> =
        when {
            from == to -> TransferError.SameAccount(from.value).left()
            !amount.isPositive -> TransferError.InvalidTransferAmount(amount).left()
            else -> timed("bank.ask.duration", "entity" to "transfer") {
                transfers(id.value).ask<TransferMessage, TransferAnswer>(transferWait) {
                    TransferAsk(TransferRequest.Start(from, to, amount), it)
                }.fold(
                    { failure ->
                        when (failure) {
                            // Not heard of means the start has not landed yet, lost with a node or still on its way:
                            // the same id again is safe, so it is Unavailable and not a transfer that does not exist.
                            AskFailure.TimedOut -> transferStatus(id).mapLeft { refusal ->
                                if (refusal is TransferError.NoSuchTransfer) {
                                    Unavailable("transfer $id did not start in time; send it again with the same id")
                                } else {
                                    refusal
                                }
                            }
                            AskFailure.Stopped, AskFailure.Unreachable -> unavailable("transfer $id", failure).left()
                        }
                    },
                    { it },
                )
            }
        }

    override fun transferStatus(id: TransferId): Either<TransferError, TransferView> =
        timed("bank.ask.duration", "entity" to "transfer") {
            transfers(id.value).ask<TransferMessage, TransferAnswer>(askTimeout) { TransferAsk(TransferRequest.Status, it) }
                .fold({ failure -> unavailable("transfer $id", failure).left() }, { it })
        }
}

private fun unavailable(what: String, failure: AskFailure) = Unavailable(
    when (failure) {
        AskFailure.TimedOut -> "$what did not answer in time"
        AskFailure.Stopped -> "$what stopped before answering"
        AskFailure.Unreachable -> "$what is on a node that cannot be reached"
    },
)

