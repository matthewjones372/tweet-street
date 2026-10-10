package bank.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.math.BigDecimal
import java.math.RoundingMode

// What an account may pay out in a UTC day, withdrawals and transfers' debits together (bank spec 0027).

/** 10,000 in [currency], at its places: the limit of an account opened without one. */
fun defaultDailyLimit(currency: Currency): Money = Money.of(DEFAULT_DAILY_LIMIT, currency, RoundingMode.UNNECESSARY)

/** The account's limit: the one it opened with, or the default for an `Opened` written before limits. */
val Account.Open.limit: Money get() = dailyLimit ?: defaultDailyLimit(balance.currency)

/** What the account may still pay out on the UTC day of [now]. */
fun Account.Open.leftOn(now: Long): Money {
    val zero = Money.zero(balance.currency)
    val spent = paidOut?.takeIf { paidOutOn == utcDay(now) } ?: zero
    return maxOf(limit - spent, zero)
}

/** An account opening, with the limit it will keep written into its `Opened`. */
internal fun opening(id: AccountId, command: AccountCommand.Open, now: Long): Either<AccountError, List<AccountEvent>> {
    val initial = command.initial
    val limit = command.dailyLimit ?: defaultDailyLimit(initial.currency)
    return when {
        initial.isNegative -> AccountError.InvalidAmount(initial).left()
        limit.currency != initial.currency ->
            AccountError.CurrencyMismatch(id.value, initial.currency.code, limit.currency.code).left()
        limit.isNegative -> AccountError.InvalidAmount(limit).left()
        else -> listOf(AccountEvent.Opened(command.owner, initial, command.reference, now, limit)).right()
    }
}

/** [amount] paid out at [atMillis]: added to that UTC day's total, which starts from nothing on a new day. */
internal fun Account.Open.paid(amount: Money, atMillis: Long, transfer: TransferId? = null): Account.Open {
    val day = utcDay(atMillis)
    val sameDay = day == paidOutOn && paidOut != null
    val before = if (sameDay) paidOut else Money.zero(amount.currency)
    val debits = if (sameDay) debitedOn else emptySet()
    return copy(paidOutOn = day, paidOut = before + amount, debitedOn = transfer?.let { debits + it.value } ?: debits)
}

/** A refund takes its debit back off the day's total, if that debit is in it: the money came back. */
internal fun Account.Open.givenBack(transfer: TransferId, amount: Money, atMillis: Long): Account.Open =
    if (utcDay(atMillis) != paidOutOn || transfer.value !in debitedOn || paidOut == null) this
    else copy(paidOut = maxOf(paidOut - amount, Money.zero(amount.currency)), debitedOn = debitedOn - transfer.value)

/** The UTC day [millis] falls on, counted from the epoch. */
internal fun utcDay(millis: Long): Long = Math.floorDiv(millis, DAY_MILLIS)

private val DEFAULT_DAILY_LIMIT = BigDecimal(10_000)

private const val DAY_MILLIS = 86_400_000L
