package bank.approvals.app

import bank.approvals.api.NewRequest
import bank.approvals.api.ask
import bank.events.v1.ApprovalEvent.EventCase
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.HexFormat
import java.util.UUID

/** An OTLP endpoint that keeps every batch it is sent, as bytes: a trace id is in them as its sixteen raw bytes. */
private class Collector : AutoCloseable {
    val batches = ByteArrayOutputStream()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/traces") { exchange ->
            synchronized(batches) { batches.write(exchange.requestBody.readAllBytes()) }
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    fun holds(trace: String): Boolean {
        val wanted = HexFormat.of().parseHex(trace)
        val held = synchronized(batches) { batches.toByteArray() }
        return (0..held.size - wanted.size).any { at -> wanted.indices.all { held[at + it] == wanted[it] } }
    }

    override fun close() = server.stop(0)
}

/** lark-bank spec 0024's `trace-calls`, Approvals' half: a request continues its caller's trace, and is exported. */
class TraceSpec {
    @Test
    fun `a request in a sampled trace is exported in it, and one in an unsampled trace is not`() {
        Collector().use { collector ->
            val config = """
                approvals.telemetry.enabled = true
                approvals.telemetry.otlp = "${collector.url}"
                approvals.telemetry.sampled = 0.0
            """.trimIndent()
            TestCluster(1, extra = config).node(0).use { node: TestNode ->
                val http = HttpClient.newHttpClient()
                fun asked(trace: String, flags: String) = http.send(
                    HttpRequest.newBuilder(URI.create("${node.server.baseUrl}/me"))
                        .header("Authorization", "Bearer ${TestIdentity.token("rae", "engineers")}")
                        .header("traceparent", "00-$trace-00f067aa0ba902b7-$flags")
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()
                val sampled = UUID.randomUUID().toString().replace("-", "")
                val unsampled = UUID.randomUUID().toString().replace("-", "")
                asked(sampled, "01") shouldBe 200
                asked(unsampled, "00") shouldBe 200

                eventually { collector.holds(sampled) } shouldBe true
                collector.holds(unsampled) shouldBe false
            }
        }
    }

    @Test
    fun `an event written in a request's trace is published with that trace, for its readers to continue`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val trace = UUID.randomUUID().toString().replace("-", "")
            val subject = "test/traced-${trace.take(8)}"
            val view = node.calling("rae", "engineers").inTrace("00-$trace-00f067aa0ba902b7-01")
                .outcome(ask, NewRequest("test.change", subject, "a rule", before = "1000", after = "500")).shouldBeOk()

            val requested = TestKafka.records(setOf(view.id)) { seen -> seen.isNotEmpty() }.first()
            requested.value().eventCase shouldBe EventCase.REQUESTED
            val traceparent = requested.headers().lastHeader("traceparent")?.value()?.toString(Charsets.UTF_8)
            traceparent?.split("-")?.get(1) shouldBe trace
        }.shouldBeRight()
    }
}
