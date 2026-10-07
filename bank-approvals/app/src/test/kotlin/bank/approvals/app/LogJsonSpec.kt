package bank.approvals.app

import bank.approvals.api.NewRequest
import bank.approvals.api.ask
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

/** lark-bank spec 0023's `log-json`: every line Approvals writes is one JSON object, with the estate's field names. */
class LogJsonSpec {
    private val run = UUID.randomUUID().toString().take(8)
    private val json = ObjectMapper()

    /** What [block] wrote to stdout under the service's own `logback.xml`, the tests' configuration put back after. */
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
    fun `every line a node writes, migrating, starting and answering, is one JSON object with the shared fields`() {
        val lines = written {
            TestCluster(1).node(0).use { node: TestNode ->
                node.calling("rae", "engineers")
                    .outcome(ask, NewRequest("test.change", "test/logged-$run", "a rule", before = "1000", after = "500"))
                    .shouldBeOk()
                logAnnotated("trace_id" to "4bf92f3577b34da6a3ce929d0e0e4736", "request_id" to "r-$run") {
                    logWarn("a line about one request")
                }
            }
        }
        lines.shouldNotBeEmpty()
        val parsed = lines.map { line -> withClue(line) { json.readTree(line) } }
        parsed.forEach { line: JsonNode ->
            withClue(line.toString()) {
                listOf("ts", "level", "service", "msg").forEach { line.path(it).asText().shouldNotBeBlank() }
                line.path("service").asText() shouldBe "bank-approvals"
            }
        }
        val annotated = parsed.single { it.path("msg").asText() == "a line about one request" }
        annotated.path("trace_id").asText() shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
        annotated.path("request_id").asText() shouldBe "r-$run"
    }
}
