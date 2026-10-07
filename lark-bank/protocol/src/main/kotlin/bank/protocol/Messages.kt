package bank.protocol

import arrow.core.Either
import bank.domain.AccountCommand
import bank.domain.AccountError
import bank.domain.AccountId
import bank.domain.Balance
import bank.domain.Money
import bank.domain.ScreeningDecision
import bank.domain.Step
import bank.domain.TransferError
import bank.domain.TransferView
import io.github.matthewjones372.lark.actor.Delivered
import io.github.matthewjones372.lark.actor.Delivery
import io.github.matthewjones372.lark.actor.Reply

/** What an account answers: its balance, or why not. */
typealias AccountAnswer = Either<AccountError, Balance>

/** What a transfer answers: where it has got to, or why not. */
typealias TransferAnswer = Either<TransferError, TransferView>

/** What an account entity is sent. */
sealed interface AccountMessage

/** A command, or a balance enquiry when [command] is null, from HTTP or from one leg of a transfer. */
data class AccountAsk(val command: AccountCommand?, val reply: Reply<AccountAnswer>) : AccountMessage

/** A credit sent in bulk through a reliable producer: no reply, confirmed once written. */
data class BulkCredit(val amount: Money, val reference: String)

/** A credit sent in bulk and confirmed rather than answered, so a resend after a move is dropped, not applied twice. */
data class BulkCreditSent(val credit: BulkCredit, override val delivery: Delivery) : AccountMessage, Delivered

/** What a transfer saga is asked. */
sealed interface TransferRequest {
    /** The same start twice is one transfer. */
    data class Start(val from: AccountId, val to: AccountId, val amount: Money) : TransferRequest

    /** How far it has got. */
    data object Status : TransferRequest
}

/** What a transfer saga is sent. */
sealed interface TransferMessage

data class TransferAsk(val request: TransferRequest, val reply: Reply<TransferAnswer>) : TransferMessage

/** A saga that stopped moving, woken by the sweeper: it looks at its state and takes the next step. */
data object Nudge : TransferMessage

/** An account's answer to one of the saga's legs. Only ever sent by the saga to itself. */
data class LegAnswered(val step: Step, val answer: AccountAnswer) : TransferMessage

/** The leg in flight took too long, so the saga asks again; each leg is idempotent by transfer id. */
data class LegTimedOut(val step: Step) : TransferMessage

/** Screening's decision about the transfer (spec 0018). Only ever sent by the saga to itself. */
data class ScreeningAnswered(val decision: ScreeningDecision) : TransferMessage

/** Screening did not answer in time, or failed: the bank's policy decides instead. Only ever sent to itself. */
data object ScreeningUnanswered : TransferMessage
