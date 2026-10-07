package bank.protocol

import bank.domain.Currency
import bank.domain.Money

val GBP = Currency("GBP", 2)
val BTC = Currency("BTC", 8)

fun gbp(minor: Long) = Money.ofMinor(minor, GBP)
