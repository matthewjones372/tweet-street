package bank.domain

val GBP = Currency("GBP", 2)
val JPY = Currency("JPY", 0)
val BTC = Currency("BTC", 8)
val ETH = Currency("ETH", 9)

fun gbp(minor: Long) = Money.ofMinor(minor, GBP)
