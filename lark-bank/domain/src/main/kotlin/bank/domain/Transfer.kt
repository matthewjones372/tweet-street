package bank.domain

/**
 * A transfer between two accounts that may live on different nodes, so no one actor can move the money
 * in one step. It is a saga: screen it (spec 0018), debit the source, credit the destination, and if the credit is
 * refused give the debit back. Every step is an event, so a transfer whose node dies mid-way resumes where it stopped.
 */
sealed interface TransferEvent {
    val atMillis: Long

    data class Requested(val from: AccountId, val to: AccountId, val amount: Money, override val atMillis: Long) :
        TransferEvent

    /**
     * Screening's decision (spec 0018), or the bank's own when screening did not answer in time ([answered] false). A
     * decline names the [rule] and [version] that declined it; [evidence] is why, as the check wrote it.
     */
    data class Screened(
        val outcome: ScreeningOutcome,
        val rule: String?,
        val version: Int?,
        val evidence: String,
        val answered: Boolean,
        override val atMillis: Long,
    ) : TransferEvent

    data class SourceDebited(override val atMillis: Long) : TransferEvent

    /** The source said no: nothing moved, and nothing needs undoing. */
    data class Rejected(val reason: String, override val atMillis: Long) : TransferEvent

    data class DestinationCredited(override val atMillis: Long) : TransferEvent

    /** The destination said no after the source paid: the money is in flight until it is refunded. */
    data class CreditRefused(val reason: String, override val atMillis: Long) : TransferEvent

    data class SourceRefunded(override val atMillis: Long) : TransferEvent
}

enum class TransferStatus { Pending, Debited, Completed, Rejected, Refunding, Refunded }

enum class ScreeningOutcome { Approved, Declined }

/** What the bank does with a transfer screening did not answer for in time. */
enum class WhenUnanswered { Approve, Decline }

sealed interface Transfer {
    data object Unrequested : Transfer

    data class Moving(
        val from: AccountId,
        val to: AccountId,
        val amount: Money,
        val status: TransferStatus,
        val reason: String?,
        val requestedAt: Long,
        val settledAt: Long?,
        /** Screening has approved it, or the bank has for it: it may debit. */
        val screened: Boolean = false,
    ) : Transfer {
        val settled: Boolean
            get() = when (status) {
                TransferStatus.Completed, TransferStatus.Rejected, TransferStatus.Refunded -> true
                TransferStatus.Pending, TransferStatus.Debited, TransferStatus.Refunding -> false
            }
    }
}

fun Transfer.evolve(event: TransferEvent): Transfer = when (event) {
    is TransferEvent.Requested ->
        Transfer.Moving(event.from, event.to, event.amount, TransferStatus.Pending, null, event.atMillis, null)
    is TransferEvent.Screened -> when (event.outcome) {
        ScreeningOutcome.Approved -> moving().copy(screened = true)
        ScreeningOutcome.Declined ->
            moving().copy(status = TransferStatus.Rejected, reason = event.declined(), settledAt = event.atMillis)
    }
    is TransferEvent.SourceDebited -> moving().copy(status = TransferStatus.Debited)
    is TransferEvent.Rejected -> moving().copy(status = TransferStatus.Rejected, reason = event.reason, settledAt = event.atMillis)
    is TransferEvent.DestinationCredited -> moving().copy(status = TransferStatus.Completed, settledAt = event.atMillis)
    is TransferEvent.CreditRefused -> moving().copy(status = TransferStatus.Refunding, reason = event.reason)
    is TransferEvent.SourceRefunded -> moving().copy(status = TransferStatus.Refunded, settledAt = event.atMillis)
}

/** The reason a declined transfer gives: the rule that declined it, or that the check did not answer. */
private fun TransferEvent.Screened.declined(): String = when {
    rule != null -> "Declined by $rule, version $version"
    else -> "Declined: the check did not answer in time"
}

private fun Transfer.moving(): Transfer.Moving = when (this) {
    is Transfer.Moving -> this
    Transfer.Unrequested -> error("a transfer event before the transfer was requested")
}

/** What the saga does next, read off its state: after a restart this is how it knows where it was. */
sealed interface Step {
    /** Ask screening about the transfer before any money moves (spec 0018). */
    data class Screen(val from: AccountId, val to: AccountId, val amount: Money, val requestedAt: Long) : Step
    data class DebitSource(val account: AccountId, val command: AccountCommand.Debit) : Step
    data class CreditDestination(val account: AccountId, val command: AccountCommand.Credit) : Step
    data class RefundSource(val account: AccountId, val command: AccountCommand.Refund) : Step
    data object Done : Step
}

/**
 * With [screening] on, a transfer not yet screened screens before it debits; off, it debits as it always did. A
 * transfer already screened debits either way, so switching screening on or off mid-flight asks nothing twice.
 */
fun Transfer.next(id: TransferId, screening: Boolean = false): Step = when (this) {
    Transfer.Unrequested -> Step.Done
    is Transfer.Moving -> when (status) {
        TransferStatus.Pending ->
            if (screening && !screened) Step.Screen(from, to, amount, requestedAt) else Step.DebitSource(from, AccountCommand.Debit(id, amount))
        TransferStatus.Debited -> Step.CreditDestination(to, AccountCommand.Credit(id, amount))
        TransferStatus.Refunding -> Step.RefundSource(from, AccountCommand.Refund(id, amount))
        TransferStatus.Completed, TransferStatus.Rejected, TransferStatus.Refunded -> Step.Done
    }
}

/** How a finished step moves the saga on: the account's answer becomes the saga's next event. */
fun Transfer.Moving.after(step: Step, refusal: AccountError?, now: Long): TransferEvent? = when (step) {
    is Step.DebitSource ->
        if (refusal == null) TransferEvent.SourceDebited(now) else TransferEvent.Rejected(refusal.message, now)
    is Step.CreditDestination ->
        if (refusal == null) TransferEvent.DestinationCredited(now) else TransferEvent.CreditRefused(refusal.message, now)
    // A refund cannot be refused by an open account; if it is, the saga stays Refunding and tries again.
    is Step.RefundSource -> if (refusal == null) TransferEvent.SourceRefunded(now) else null
    is Step.Screen, Step.Done -> null
}

/** Screening's answer about one transfer, in the Ledger's terms: what its client hands back once translated. */
data class ScreeningDecision(val outcome: ScreeningOutcome, val rule: String?, val version: Int?, val evidence: String)

fun ScreeningDecision.screened(now: Long): TransferEvent.Screened =
    TransferEvent.Screened(outcome, rule, version, evidence, answered = true, atMillis = now)

/** The bank's own decision, for a transfer screening did not answer for in time. */
fun unanswered(policy: WhenUnanswered, now: Long): TransferEvent.Screened = when (policy) {
    WhenUnanswered.Approve -> TransferEvent.Screened(ScreeningOutcome.Approved, null, null, "", answered = false, atMillis = now)
    WhenUnanswered.Decline -> TransferEvent.Screened(ScreeningOutcome.Declined, null, null, "", answered = false, atMillis = now)
}

sealed interface TransferError {
    val message: String

    data class SameAccount(val id: String) : TransferError {
        override val message: String get() = "A transfer from $id to itself moves nothing"
    }

    data class InvalidTransferAmount(val amount: Money) : TransferError {
        override val message: String get() = "$amount is not an amount that can move"
    }

    data class NoSuchTransfer(val id: String) : TransferError {
        override val message: String get() = "No transfer $id"
    }

    /** The id was used before for a different transfer. The same request twice is not this: it answers as before. */
    data class TransferIdReused(val id: String) : TransferError {
        override val message: String get() = "Transfer $id was already requested with different details"
    }
}
