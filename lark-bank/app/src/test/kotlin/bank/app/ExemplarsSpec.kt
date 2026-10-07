package bank.app

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** Bank spec 0024's `trace-views`: a request's latency carries its trace as an exemplar, for a panel to open it. */
class ExemplarsSpec {
    private val http = HttpClient.newHttpClient()

    private fun hex(length: Int) = UUID.randomUUID().toString().replace("-", "").repeat(2).take(length)

    @Test
    fun `a request in a sampled trace leaves its trace id on the latency histogram, and one in an unsampled trace does not`() {
        // Spans made and sent nowhere: the exemplar is the span's, whether or not anything keeps it.
        val config = """
            bank.telemetry.enabled = true
            bank.telemetry.otlp = "http://127.0.0.1:9"
            bank.telemetry.sampled = 0.0
        """.trimIndent()
        TestCluster(1, extra = config).running { (node) ->
            fun accounts(traceparent: String): Int = http.send(
                HttpRequest.newBuilder(URI.create("${node.server.baseUrl}/accounts"))
                    .header("Authorization", "Bearer ${TestIdentity.token("ada")}")
                    .header("traceparent", traceparent)
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
            val sampled = hex(32)
            val unsampled = hex(32)
            accounts("00-$sampled-${hex(16)}-01") shouldBe 200
            accounts("00-$unsampled-${hex(16)}-00") shouldBe 200

            val metrics = http.send(
                HttpRequest.newBuilder(URI.create("${node.server.baseUrl}/metrics")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            metrics.headers().firstValue("Content-Type").orElse("") shouldStartWith "application/openmetrics-text"
            metrics.body().trimEnd() shouldEndWith "# EOF"
            val latencies = metrics.body().lines().filter { it.startsWith("http_server_request_duration_seconds_bucket") }
            latencies.filter { "trace_id=\"$sampled\"" in it }.shouldNotBeEmpty()
            latencies.filter { "trace_id=\"$unsampled\"" in it }.shouldBeEmpty()
        }
    }
}
