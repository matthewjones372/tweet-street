package bank.app

import bank.access.client.Access
import bank.access.client.Decisions
import bank.access.fga.Fga
import bank.api.Asked
import bank.api.Caller
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.lark.otel.span
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Bank spec 0024's `trace-calls`, the bank's calls not made through Pelican: bank-access's, from the shadow's thread. */
class TraceCallsSpec {
    private val tracer = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
        .getTracer("test")

    @Test
    fun `the current trace as headers, and none outside one`() {
        traceHeaders() shouldBe emptyMap()
        val (headers, trace) = tracer.span("asking") { traceHeaders() to Span.current().spanContext.traceId }
        headers.getValue("traceparent").split("-")[1] shouldBe trace
    }

    @Test
    fun `a shadowed question reaches OpenFGA in the trace of the request it shadows`() {
        val seen = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seen += exchange.requestHeaders.getFirst("traceparent").orEmpty()
                val body = if (exchange.requestURI.path == "/stores") """{"stores":[{"name":"bank","id":"s1"}]}""" else """{"allowed":true}"""
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        try {
            val fga = Fga("http://127.0.0.1:${server.address.port}", "key", "bank", Access.BUDGET, carried = ::traceHeaders)
            val shadow = ShadowedAccess(Access(fga, Decisions.none, SimpleMeterRegistry()), 1.seconds)
            val ada = Caller("ada", "Ada", emptySet())

            val trace = tracer.span("viewing") {
                shadow.compare(Asked.ViewAccount, ada, "acc-1", local = true)
                Span.current().spanContext.traceId
            }

            val sent = generateSequence { seen.poll(10, TimeUnit.SECONDS) }.take(2).toList()
            sent.shouldNotBeEmpty()
            sent.forEach { it.split("-")[1] shouldBe trace }
        } finally {
            server.stop(0)
        }
    }
}
