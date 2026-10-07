package bank.load

import bank.api.Decision
import bank.api.screen
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.start
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A stand-in for the screening check (bank spec 0018), for measuring the bank with screening on until the real one
 * runs beside it: `POST /screen` as the bank describes it, declining pounds of `DECLINE_FROM` or more, and answering
 * a transfer asked about twice with its first decision, as the check does. Decisions are kept in memory only.
 */
fun main() {
    val declineFrom = BigDecimal(System.getenv("DECLINE_FROM") ?: "150")
    val decided = ConcurrentHashMap<String, Decision>()
    val asked = AtomicLong()
    val declined = AtomicLong()
    val port = System.getenv("PORT")?.toInt() ?: 8090
    val check = api(
        endpoints = listOf(
            screen handledNow { proposed ->
                asked.incrementAndGet()
                decided.computeIfAbsent(proposed.transfer) {
                    val amount = BigDecimal(proposed.amount.value)
                    if (proposed.amount.currency == "GBP" && amount >= declineFrom) {
                        declined.incrementAndGet()
                        Decision("declined", "large-transfer", 1, "amount ${proposed.amount.value} >= $declineFrom")
                    } else {
                        Decision("approved", null, null, "no rule held")
                    }
                }
            },
        ),
        codecs = JacksonCodecs,
    )
    check.start(port = port, host = "0.0.0.0")
    println("stand-in check: on $port, declining GBP $declineFrom or more")
    while (true) {
        Thread.sleep(10_000)
        println("stand-in check: ${asked.get()} asked, ${decided.size} decided, ${declined.get()} declined")
    }
}
