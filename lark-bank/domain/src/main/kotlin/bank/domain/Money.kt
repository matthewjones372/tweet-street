package bank.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A currency as the ledger needs it: its code, and how many places its minor unit is (bank spec 0011). The places are
 * the bank's own, not ISO 4217's, up to 18: ETH to the gwei, 9, though an amount would hold it to the wei.
 */
data class Currency(val code: String, val exponent: Int) {
    init {
        require(code.matches(CODE)) { "a currency code is 3 to 5 capital letters, was '$code'" }
        require(exponent in 0..MAX_EXPONENT) { "$code's places must be 0 to $MAX_EXPONENT, was $exponent" }
    }

    enum class Kind { Fiat, Crypto }

    override fun toString(): String = code

    private companion object {
        val CODE = Regex("[A-Z]{3,5}")
        const val MAX_EXPONENT = 18
    }
}

/**
 * An amount, held as a `BigDecimal` at exactly its currency's places (bank spec 0016), so two amounts of one currency
 * always add and compare exactly and `equals` agrees with `compareTo`. Only ever added to the same currency, which is
 * a bug to get wrong rather than a caller's error; accounts refuse another currency before it gets here.
 */
data class Money(val amount: BigDecimal, val currency: Currency) : Comparable<Money> {
    init {
        require(amount.scale() == currency.exponent) { "$amount is not at $currency's ${currency.exponent} places" }
        require(amount.abs() < LIMIT) { "$amount $currency is past the ${LIMIT.toPlainString()} any amount may reach" }
    }

    operator fun plus(other: Money) = Money(amount + same(other).amount, currency)
    operator fun minus(other: Money) = Money(amount - same(other).amount, currency)
    operator fun unaryMinus() = Money(amount.negate(), currency)
    override fun compareTo(other: Money): Int = amount.compareTo(same(other).amount)
    val isPositive: Boolean get() = amount.signum() > 0
    val isNegative: Boolean get() = amount.signum() < 0

    override fun toString(): String = "${amount.toPlainString()} $currency"

    private fun same(other: Money): Money =
        other.also { require(it.currency == currency) { "$this and $other are different currencies" } }

    companion object {
        private val DECIMAL = Regex("""(\d+)(?:\.(\d+))?""")

        /** The one bound on an amount, where a `Long` of minor units had its own: one bad input cannot poison a total. */
        val LIMIT: BigDecimal = BigDecimal.TEN.pow(30)

        fun zero(currency: Currency) = Money(BigDecimal.ZERO.setScale(currency.exponent), currency)

        /** [minor] whole minor units, as the journal's first version kept an amount. */
        fun ofMinor(minor: Long, currency: Currency) = Money(BigDecimal.valueOf(minor, currency.exponent), currency)

        /** A plain decimal, "12.50", with no more places than [currency] has: an amount is never rounded on the way in. */
        fun parse(value: String, currency: Currency): Either<UnreadableAmount, Money> {
            val unreadable = UnreadableAmount(value, currency.code)
            val match = DECIMAL.matchEntire(value) ?: return unreadable.left()
            if (match.groupValues[2].length > currency.exponent) return unreadable.left()
            val amount = BigDecimal(value).setScale(currency.exponent)
            return if (amount >= LIMIT) unreadable.left() else Money(amount, currency).right()
        }

        /** A rate's result, rounded once to the currency's places as [rounding] says: every caller names its rounding. */
        fun of(amount: BigDecimal, currency: Currency, rounding: RoundingMode): Money =
            Money(amount.setScale(currency.exponent, rounding), currency)
    }
}

/** A string that is not an amount of that currency: not a plain decimal, too many places, or past the limit. */
data class UnreadableAmount(val value: String, val currency: String) {
    val message: String get() = "'$value' is not an amount of $currency"
}

data class UnknownCurrency(val code: String) {
    val message: String get() = "The bank keeps no $code"
}

/** A currency the bank offers, and how it is shown. */
data class Listed(val currency: Currency, val symbol: String, val kind: Currency.Kind = Currency.Kind.Fiat)

/** The currencies the bank keeps (bank spec 0011): configuration, read once, and passed to whatever needs it. */
class Currencies(listed: List<Listed>) {
    private val byCode = listed.associateBy { it.currency.code }

    init {
        require(byCode.size == listed.size) { "a currency is listed twice: ${listed.map { it.currency.code }}" }
    }

    val all: List<Listed> = listed

    fun of(code: String): Either<UnknownCurrency, Currency> = byCode[code]?.currency?.right() ?: UnknownCurrency(code).left()

    fun listed(currency: Currency): Listed? = byCode[currency.code]?.takeIf { it.currency == currency }

    /** "£12.50", "₿0.00125000": the symbol, and every place the currency has. */
    fun format(money: Money): String {
        val symbol = listed(money.currency)?.symbol ?: "${money.currency.code} "
        val sign = if (money.isNegative) "-" else ""
        return "$sign$symbol${money.amount.abs().toPlainString()}"
    }
}

@JvmInline
value class AccountId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class TransferId(val value: String) {
    override fun toString(): String = value
}
