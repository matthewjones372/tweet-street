package bank.app

import bank.domain.AccountId
import bank.domain.Bank
import bank.domain.Money
import bank.domain.TransferId
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.otel.tracedSpan
import io.opentelemetry.api.trace.Tracer

/**
 * A span per transfer, with the transfer's id on every line written inside it. The accounts' calls are not
 * wrapped: at thousands a second a span each is the tracer's load, not the bank's.
 */
class TracedBank(private val bank: Bank, private val tracer: Tracer) : Bank by bank {
    override fun transfer(id: TransferId, from: AccountId, to: AccountId, amount: Money) =
        logAnnotated("transfer_id" to id.value) { tracer.tracedSpan("transfer") { bank.transfer(id, from, to, amount) } }
}
