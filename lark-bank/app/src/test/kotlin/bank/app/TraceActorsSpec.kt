package bank.app

import bank.domain.AccountId
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** A trace as Tempo holds it: each span's name, and the node that made it. */
private class Held(val spans: List<Pair<String, String>>) {
    val names get() = spans.map { it.first }
    val nodes get() = spans.map { it.second }.toSet()
}

/** Bank spec 0024's `trace-actors`: a transfer's trace follows it to the accounts' nodes. */
class TraceActorsSpec {
    private val run = UUID.randomUUID().toString().take(8)
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private fun held(trace: String): Held {
        val asked = HttpRequest.newBuilder(URI.create("${TestTempo.api}/api/v2/traces/$trace")).build()
        val answer = http.send(asked, HttpResponse.BodyHandlers.ofString())
        if (answer.statusCode() == 404) return Held(emptyList())
        val spans = json.readTree(answer.body()).findValues("resourceSpans").flatMap { it.toList() }.flatMap { resource ->
            val node = resource.path("resource").path("attributes")
                .first { it.path("key").asText() == "service.instance.id" }.path("value").path("stringValue").asText()
            resource.findValues("spans").flatMap { spans: JsonNode -> spans.map { it.path("name").asText() to node } }
        }
        return Held(spans)
    }

    @Test
    fun `a transfer's trace holds its debit and credit, made on the nodes the accounts live on`() {
        val config = """
            bank.telemetry.enabled = true
            bank.telemetry.otlp = "${TestTempo.otlp}"
            bank.telemetry.sampled = 0.0
        """.trimIndent()
        TestCluster(3, extra = config).running { nodes ->
            val first = nodes.first()
            val traces = (1..6).map { n ->
                val from = "from-$n-$run"
                val to = "to-$n-$run"
                first.bank.open(AccountId(from), "ada", gbp(5_000), "open:$from").shouldBeRight()
                first.bank.open(AccountId(to), "bob", gbp(0), "open:$to").shouldBeRight()
                val trace = UUID.randomUUID().toString().replace("-", "")
                val status = http.send(
                    HttpRequest.newBuilder(URI.create("${first.server.baseUrl}/transfers/t-$n-$run"))
                        .header("Authorization", "Bearer ${TestIdentity.token("ada")}")
                        .header("Content-Type", "application/json")
                        .header("traceparent", "00-$trace-00f067aa0ba902b7-01")
                        .PUT(HttpRequest.BodyPublishers.ofString("""{"from":"$from","to":"$to","amount":{"value":"1.00","currency":"GBP"}}"""))
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()
                status shouldBe 200
                trace
            }

            val until = System.nanoTime() + 60_000_000_000
            fun complete(trace: String) = held(trace).names.containsAll(listOf("account debit", "account credit"))
            while (!traces.all(::complete) && System.nanoTime() < until) Thread.sleep(500)

            val all = traces.map(::held)
            all.forEach { trace ->
                withClue(trace.names) {
                    trace.names shouldContainAll listOf("PUT /transfers/{transferId}", "transfer debitsource", "account debit", "account credit")
                }
            }
            withClue("six transfers, two accounts each, on three nodes: some leg is on another node") {
                all.maxOf { it.nodes.size } shouldBeGreaterThan 1
            }
        }
    }
}
