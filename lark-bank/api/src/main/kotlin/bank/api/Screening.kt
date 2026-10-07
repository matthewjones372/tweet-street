package bank.api

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody

// The one endpoint of the check the bank calls (spec 0018): Screening's open host service, described from the
// Ledger's side. The client in `bank.api.screening` is generated from it; ScreeningClientSpec keeps the two in step.

/** A transfer that has not debited yet, as Screening is asked about it. */
data class ProposedTransfer(
    val transfer: String,
    val from: String,
    val to: String,
    val amount: Amount,
    val requestedAtMillis: Long,
)

/**
 * Screening's decision: `approved`, or `declined` by [rule] at [version]. [evidence] is the check's account of why.
 * The same transfer asked about twice answers the first decision.
 */
data class Decision(val outcome: String, val rule: String?, val version: Int?, val evidence: String)

val screen = endpoint(jsonBody<ProposedTransfer>()) {
    post("screen")
    operationId = "screen"
    summary = "Decide, once and for good, whether a proposed transfer may move"
    json<Decision>()
}

fun screeningSpec(): ApiSpec = apiSpec(listOf(screen), schemas = JacksonCodecs) {
    title = "Screening"
    version = "0.1.0"
}
