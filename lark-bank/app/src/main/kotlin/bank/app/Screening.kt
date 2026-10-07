package bank.app

import arrow.core.Either
import bank.api.screening.Amount
import bank.api.screening.ProposedTransfer
import bank.api.screening.ScreeningClient
import bank.domain.ScreeningDecision
import bank.domain.ScreeningOutcome
import bank.domain.Step
import bank.domain.TransferId
import bank.domain.WhenUnanswered
import io.github.matthewjones372.lark.timed
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import io.opentelemetry.api.OpenTelemetry
import io.github.matthewjones372.pelican.metrics.otel.traced

/** Why screening gave no decision: it failed, or answered something the bank cannot read. The policy decides then. */
data class NoDecision(val why: String)

/** Screening, as the Ledger asks it about a transfer before it debits (spec 0018). Blocks: never on an entity's thread. */
fun interface Screener {
    fun screen(id: TransferId, proposed: Step.Screen): Either<NoDecision, ScreeningDecision>
}

/** Whether transfers are screened, and if they are, through what, how long the saga waits, and what it does then. */
sealed interface Screening {
    data object Off : Screening

    data class On(val screener: Screener, val timeout: Duration, val whenUnanswered: WhenUnanswered) : Screening
}

fun screening(settings: ScreeningSettings, telemetry: OpenTelemetry = OpenTelemetry.noop()): Screening =
    if (!settings.enabled) Screening.Off
    else Screening.On(PelicanScreener(settings.url, settings.timeout, telemetry), settings.timeout, settings.whenUnanswered)

/**
 * The check's `POST /screen`, through the client generated from its description, translated at this edge: nothing
 * of Screening's own types gets past it.
 */
class PelicanScreener(url: String, timeout: Duration, telemetry: OpenTelemetry = OpenTelemetry.noop()) : Screener {
    // Each call a client span, its traceparent sent, so the check continues the transfer's trace (bank spec 0024).
    private val transport = PekkoHttpTransport().traced(telemetry)
    private val client = ScreeningClient(url, JacksonCodecs, transport, timeout.toJavaDuration())

    init {
        // The client's first call starts its actor system and opens a connection, about 450 ms: longer than the
        // timeout, so the first transfer after every start would go unscreened. The check's /health pays it instead.
        transport.send(ClientRequest(Method.GET, url.trimEnd('/') + "/health"))
            .whenComplete { response, _ -> response?.body?.close() }
    }

    override fun screen(id: TransferId, proposed: Step.Screen): Either<NoDecision, ScreeningDecision> {
        val asked = ProposedTransfer(
            transfer = id.value,
            from = proposed.from.value,
            to = proposed.to.value,
            amount = Amount(proposed.amount.amount.toPlainString(), proposed.amount.currency.code),
            requestedAtMillis = proposed.requestedAt,
        )
        // Whatever goes wrong on the way, the answer is the same: no decision, and the bank's policy applies.
        val decision = try {
            timed("bank.screening.duration") { client.screen(asked) }
        } catch (failed: Exception) {
            return Either.Left(NoDecision(failed.message ?: failed::class.java.name))
        }
        val outcome = when (decision.outcome) {
            "approved" -> ScreeningOutcome.Approved
            "declined" -> ScreeningOutcome.Declined
            else -> return Either.Left(NoDecision("an outcome the bank does not know: ${decision.outcome}"))
        }
        return Either.Right(ScreeningDecision(outcome, decision.rule, decision.version, decision.evidence))
    }
}
