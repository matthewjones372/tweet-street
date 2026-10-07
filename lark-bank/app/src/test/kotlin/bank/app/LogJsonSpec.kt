package bank.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logWarn
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import bank.domain.AccountId

/** Bank spec 0023's `log-json`: every line the bank writes is one JSON object, with the estate's field names. */
class LogJsonSpec {
    private val run = UUID.randomUUID().toString().take(8)
    private val json = ObjectMapper()

    /** What [block] wrote to stdout under the bank's own `logback.xml`, the tests' configuration put back after. */
    private fun written(block: () -> Unit): List<String> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        fun configure(name: String) {
            context.reset()
            JoranConfigurator().apply { setContext(context) }.doConfigure(requireNotNull(javaClass.classLoader.getResource(name)))
        }
        val out = ByteArrayOutputStream()
        val before = System.out
        System.setOut(PrintStream(out, true))
        try {
            configure("logback.xml")
            block()
        } finally {
            configure("logback-test.xml")
            System.setOut(before)
        }
        return out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    @Test
    fun `every line a node writes, starting, serving and refusing, is one JSON object with the shared fields`() {
        val acc = "acc-$run"
        val lines = written {
            TestCluster(1).running { (node) ->
                node.bank.open(AccountId(acc), "ada", gbp(5_000), "open:$acc").shouldBeRight()
                val http = HttpClient.newHttpClient()
                fun get(path: String, who: String) = http.send(
                    HttpRequest.newBuilder(URI.create(node.server.baseUrl + path)).header("Authorization", "Bearer ${TestIdentity.token(who)}").build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()
                get("/accounts/$acc", "ada") shouldBe 200
                get("/accounts/$acc", "eve") shouldBe 404
                // Lark's annotations, as a span's ids are put on its lines, become fields of their own.
                logAnnotated("trace_id" to "4bf92f3577b34da6a3ce929d0e0e4736", "transfer_id" to "t-$run") {
                    logWarn("a line inside a transfer's span")
                }
            }
        }
        lines.shouldNotBeEmpty()
        val parsed = lines.map { line -> withClue(line) { json.readTree(line) } }
        parsed.forEach { line: JsonNode ->
            withClue(line.toString()) {
                listOf("ts", "level", "service", "msg").forEach { line.path(it).asText().shouldNotBeBlank() }
                line.path("service").asText() shouldBe "lark-bank"
            }
        }
        val annotated = parsed.single { it.path("msg").asText() == "a line inside a transfer's span" }
        annotated.path("level").asText() shouldBe "WARN"
        annotated.path("trace_id").asText() shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
        annotated.path("transfer_id").asText() shouldBe "t-$run"
    }
}
