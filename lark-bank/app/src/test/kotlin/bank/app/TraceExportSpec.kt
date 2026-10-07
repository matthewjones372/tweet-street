package bank.app

import bank.domain.AccountId
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** Tempo for the test JVM (bank spec 0024): compose's own configuration, its blocks inside the container. */
object TestTempo {
    private val server by lazy {
        GenericContainer("grafana/tempo:3.1.0")
            .withCopyToContainer(MountableFile.forHostPath("../deploy/docker/tempo.yaml"), "/etc/tempo.yaml")
            .withCommand("-config.file=/etc/tempo.yaml", "-target=all")
            .withExposedPorts(3200, 4318)
            .waitingFor(Wait.forHttp("/ready").forPort(3200).withStartupTimeout(Duration.ofMinutes(2)))
            .apply { start() }
    }
    val otlp: String get() = "http://${server.host}:${server.getMappedPort(4318)}"
    val api: String get() = "http://${server.host}:${server.getMappedPort(3200)}"
}

/** Bank spec 0024's `trace-export`: a request's spans reach Tempo, continuing the caller's trace, as sampled. */
class TraceExportSpec {
    private val run = UUID.randomUUID().toString().take(8)
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private fun traceId() = UUID.randomUUID().toString().replace("-", "")

    /** The names of the spans Tempo holds for [trace]: none, for one it has not got. */
    private fun spans(trace: String): List<String> {
        val asked = HttpRequest.newBuilder(URI.create("${TestTempo.api}/api/v2/traces/$trace")).build()
        val answer = http.send(asked, HttpResponse.BodyHandlers.ofString())
        if (answer.statusCode() == 404) return emptyList()
        check(answer.statusCode() == 200) { "Tempo answered ${answer.statusCode()}: ${answer.body()}" }
        return json.readTree(answer.body()).findValues("spans").flatMap { spans -> spans.map { it.path("name").asText() } }
    }

    private fun eventually(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + 60_000_000_000
        while (!done()) {
            check(System.nanoTime() < until) { "never: $what" }
            Thread.sleep(500)
        }
    }

    @Test
    fun `a request inside a sampled trace is in Tempo, its transfer a span of it, and one in an unsampled trace is not`() {
        val from = "from-$run"
        val to = "to-$run"
        val config = """
            bank.telemetry.enabled = true
            bank.telemetry.otlp = "${TestTempo.otlp}"
            bank.telemetry.sampled = 0.0
        """.trimIndent()
        TestCluster(1, extra = config).running { (node) ->
            node.bank.open(AccountId(from), "ada", gbp(5_000), "open:$from").shouldBeRight()
            node.bank.open(AccountId(to), "bob", gbp(0), "open:$to").shouldBeRight()

            fun transfer(traceparent: String): Int = http.send(
                HttpRequest.newBuilder(URI.create("${node.server.baseUrl}/transfers/t-${UUID.randomUUID()}"))
                    .header("Authorization", "Bearer ${TestIdentity.token("ada")}")
                    .header("Content-Type", "application/json")
                    .header("traceparent", traceparent)
                    .PUT(HttpRequest.BodyPublishers.ofString("""{"from":"$from","to":"$to","amount":{"value":"1.00","currency":"GBP"}}"""))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

            // The node samples nothing of its own, so what reaches Tempo is what the caller sampled.
            val sampled = traceId()
            transfer("00-$sampled-00f067aa0ba902b7-01") shouldBe 200
            val unsampled = traceId()
            transfer("00-$unsampled-00f067aa0ba902b7-00") shouldBe 200

            eventually("the sampled trace in Tempo, with its transfer") { "transfer" in spans(sampled) }
            spans(sampled) shouldContain "PUT /transfers/{transferId}"
            spans(unsampled).shouldBeEmpty()
        }
    }
}
