package bank.app

import bank.api.Amount
import bank.domain.Currencies
import bank.domain.Currency
import bank.domain.Listed
import bank.domain.Money

val GBP = Currency("GBP", 2)
val BTC = Currency("BTC", 8)

fun gbp(minor: Long) = Money.ofMinor(minor, GBP)

/** An amount as the API takes it: "12.50" in pounds. */
fun pounds(value: String) = Amount(value, "GBP")

val testCurrencies = Currencies(listOf(Listed(GBP, "£"), Listed(BTC, "₿", Currency.Kind.Crypto)))
