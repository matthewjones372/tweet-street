package bank.access.sync

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** lark-bank spec 0023's `log-json`: access-sync's lines are JSON objects, with the estate's field names. */
class LogJsonSpec {
    @Test
    fun `a line, its MDC and its exception are one JSON object with the shared fields`() {
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
            MDC.putCloseable("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736").use {
                LoggerFactory.getLogger("access-sync").warn("accounts failed, starting again in 2 s", IllegalStateException("OpenFGA away"))
            }
        } finally {
            configure("logback-test.xml")
            System.setOut(before)
        }
        val lines = out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        lines.size shouldBe 1
        val line = ObjectMapper().readTree(lines.single())
        line.path("service").asText() shouldBe "access-sync"
        line.path("level").asText() shouldBe "WARN"
        line.path("msg").asText() shouldBe "accounts failed, starting again in 2 s"
        line.path("trace_id").asText() shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
        line.path("error").asText() shouldContain "IllegalStateException: OpenFGA away"
        line.path("ts").asText().endsWith("Z") shouldBe true
    }
}
