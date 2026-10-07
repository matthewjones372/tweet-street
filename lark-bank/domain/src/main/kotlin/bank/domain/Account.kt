package bank.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** What an account is asked to do. [reference] is the caller's idempotency key: the same one twice changes nothing. */
sealed interface AccountCommand {
    val reference: String

    /** Opens the account in [initial]'s currency, which it keeps: every amount after must be in it too. */
    data class Open(val owner: String, val initial: Money, override val reference: String) : AccountCommand
    data class Deposit(val amount: Money, override val reference: String) : AccountCommand
    data class Withdraw(val amount: Money, override val reference: String) : AccountCommand

    /** The first leg of a transfer: money leaves, and is in flight until the other account takes it. */
    data class Debit(val transfer: TransferId, val amount: Money) : AccountCommand {
        override val reference: String get() = "debit:$transfer"
    }

    data class Credit(val transfer: TransferId, val amount: Money) : AccountCommand {
        override val reference: String get() = "credit:$transfer"
    }

    /** A debit given back, because the credit it was for could not be made. */
    data class Refund(val transfer: TransferId, val amount: Money) : AccountCommand {
        override val reference: String get() = "refund:$transfer"
    }

    /** The transfer has settled, so none of its legs can be retried: the account may forget them (spec 0013). */
    data class Close(val transfer: TransferId) : AccountCommand {
        override val reference: String get() = "close:$transfer"
    }
}

/** What happened to an account. Every one of these is a row in the journal; the state is their fold. */
sealed interface AccountEvent {
    val reference: String
    val atMillis: Long

    data class Opened(val owner: String, val initial: Money, override val reference: String, override val atMillis: Long) :
        AccountEvent

    data class Deposited(val amount: Money, override val reference: String, override val atMillis: Long) : AccountEvent
    data class Withdrawn(val amount: Money, override val reference: String, override val atMillis: Long) : AccountEvent
    data class Debited(val transfer: TransferId, val amount: Money, override val atMillis: Long) : AccountEvent {
        override val reference: String get() = "debit:$transfer"
    }

    data class Credited(val transfer: TransferId, val amount: Money, override val atMillis: Long) : AccountEvent {
        override val reference: String get() = "credit:$transfer"
    }

    data class Refunded(val transfer: TransferId, val amount: Money, override val atMillis: Long) : AccountEvent {
        override val reference: String get() = "refund:$transfer"
    }

    /** A settled transfer's legs, forgotten. Moves no money. */
    data class LegsClosed(val transfer: TransferId, override val atMillis: Long) : AccountEvent {
        override val reference: String get() = "close:$transfer"
    }
}

/** The ways an account says no. None of them is written to the journal. */
sealed interface AccountError {
    val message: String

    data class NoSuchAccount(val id: String) : AccountError {
        override val message: String get() = "No account $id"
    }

    data class AlreadyOpen(val id: String) : AccountError {
        override val message: String get() = "Account $id is already open"
    }

    data class InsufficientFunds(val id: String, val balance: Money, val requested: Money) : AccountError {
        override val message: String get() = "Account $id has $balance, not $requested"
    }

    data class InvalidAmount(val amount: Money) : AccountError {
        override val message: String get() = "$amount is not an amount that can move"
    }

    /** An amount in another currency than the account's: refused, never converted (conversion is spec 0015's). */
    data class CurrencyMismatch(val id: String, val account: String, val given: String) : AccountError {
        override val message: String get() = "Account $id is in $account, not $given"
    }
}

/** The bank could not answer in time: a node moving, a journal slow. Worth trying again with the same reference. */
data class Unavailable(override val message: String) : AccountError, TransferError

/**
 * An account's state. [recent] holds the last [RECENT] references applied, oldest first, so a caller's retried
 * command is recognised without the state growing with every transaction the account has seen. [legs] holds the
 * references of transfer legs applied whose transfer has not yet settled: a saga retries a leg on a timer, long after
 * a busy account has moved past [RECENT] other commands, so those are kept until the saga closes them (spec 0013).
 */
sealed interface Account {
    data object Unopened : Account

    data class Open(val owner: String, val balance: Money, val recent: List<String>, val legs: Set<String> = emptySet()) :
        Account

    companion object {
        const val RECENT = 256
    }
}

/** The balance an account answers with, whether the command moved it or was a repeat. */
data class Balance(val id: String, val owner: String, val balance: Money)

/**
 * The account's decision: the events a command produces, none for a repeat, or the refusal. Pure, so every
 * rule about money is tested without an actor, a journal or a clock.
 */
fun Account.decide(id: AccountId, command: AccountCommand, now: Long): Either<AccountError, List<AccountEvent>> =
    when (this) {
        Account.Unopened -> when (command) {
            is AccountCommand.Open ->
                if (command.initial.isNegative) AccountError.InvalidAmount(command.initial).left()
                else listOf(AccountEvent.Opened(command.owner, command.initial, command.reference, now)).right()
            is AccountCommand.Deposit, is AccountCommand.Withdraw, is AccountCommand.Debit, is AccountCommand.Credit,
            is AccountCommand.Refund, is AccountCommand.Close,
            -> AccountError.NoSuchAccount(id.value).left()
        }
        is Account.Open -> when (command) {
            // Opening an open account again for the same owner is a retry; for anyone else it is a clash.
            is AccountCommand.Open ->
                if (command.owner == owner) emptyList<AccountEvent>().right() else AccountError.AlreadyOpen(id.value).left()
            is AccountCommand.Deposit -> once(command) { moving(id, command.amount) { AccountEvent.Deposited(it, command.reference, now) } }
            is AccountCommand.Credit -> once(command) { moving(id, command.amount) { AccountEvent.Credited(command.transfer, it, now) } }
            is AccountCommand.Refund -> once(command) { moving(id, command.amount) { AccountEvent.Refunded(command.transfer, it, now) } }
            is AccountCommand.Withdraw ->
                once(command) { taking(id, command.amount) { AccountEvent.Withdrawn(it, command.reference, now) } }
            is AccountCommand.Debit -> once(command) { taking(id, command.amount) { AccountEvent.Debited(command.transfer, it, now) } }
            is AccountCommand.Close ->
                if (legs.none { it.endsWith(":${command.transfer}") }) emptyList<AccountEvent>().right()
                else listOf(AccountEvent.LegsClosed(command.transfer, now)).right()
        }
    }

/** Nothing, if [command]'s reference was applied recently: the same command twice moves the money once. */
private inline fun Account.Open.once(
    command: AccountCommand,
    decide: () -> Either<AccountError, List<AccountEvent>>,
): Either<AccountError, List<AccountEvent>> =
    if (command.reference in recent || command.reference in legs) emptyList<AccountEvent>().right() else decide()

private fun Account.Open.moving(
    id: AccountId,
    amount: Money,
    event: (Money) -> AccountEvent,
): Either<AccountError, List<AccountEvent>> = when {
    amount.currency != balance.currency -> AccountError.CurrencyMismatch(id.value, balance.currency.code, amount.currency.code).left()
    !amount.isPositive -> AccountError.InvalidAmount(amount).left()
    else -> listOf(event(amount)).right()
}

private fun Account.Open.taking(
    id: AccountId,
    amount: Money,
    event: (Money) -> AccountEvent,
): Either<AccountError, List<AccountEvent>> = when {
    amount.currency != balance.currency -> AccountError.CurrencyMismatch(id.value, balance.currency.code, amount.currency.code).left()
    !amount.isPositive -> AccountError.InvalidAmount(amount).left()
    amount > balance -> AccountError.InsufficientFunds(id.value, balance, amount).left()
    else -> listOf(event(amount)).right()
}

/** The state after an event. Recovery is this, folded over the journal from the newest snapshot. */
fun Account.evolve(event: AccountEvent): Account = when (event) {
    is AccountEvent.Opened -> Account.Open(event.owner, event.initial, listOf(event.reference))
    is AccountEvent.Deposited -> open().moved(event.amount, event.reference)
    is AccountEvent.Credited -> open().moved(event.amount, event.reference).leg(event.reference)
    is AccountEvent.Refunded -> open().moved(event.amount, event.reference).leg(event.reference)
    is AccountEvent.Withdrawn -> open().moved(-event.amount, event.reference)
    is AccountEvent.Debited -> open().moved(-event.amount, event.reference).leg(event.reference)
    is AccountEvent.LegsClosed -> open().let { it.copy(legs = it.legs.filterNot { leg -> leg.endsWith(":${event.transfer}") }.toSet()) }
}

private fun Account.Open.leg(reference: String): Account.Open = copy(legs = legs + reference)

private fun Account.open(): Account.Open = when (this) {
    is Account.Open -> this
    // A journal whose first event is not Opened is a corrupt journal, not a case to handle.
    Account.Unopened -> error("an event for an account that was never opened")
}

private fun Account.Open.moved(by: Money, reference: String): Account.Open =
    copy(balance = balance + by, recent = (recent + reference).takeLast(Account.RECENT))

fun Account.balanceOf(id: AccountId): Either<AccountError, Balance> = when (this) {
    Account.Unopened -> AccountError.NoSuchAccount(id.value).left()
    is Account.Open -> Balance(id.value, owner, balance).right()
}
