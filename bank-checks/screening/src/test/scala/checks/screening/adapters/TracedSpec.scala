package checks.screening.adapters

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio.*
import zio.http.*
import zio.test.*

import scala.jdk.CollectionConverters.*

/**
 * lark-bank spec 0024's `trace-calls`, the check's half: screening continues
 * the caller's trace.
 */
object TracedSpec extends ZIOSpecDefault:
  private def telemetry(spans: InMemorySpanExporter) = OpenTelemetrySdk
    .builder()
    .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
    .build()

  private val answering = Routes(Method.POST / "screen" -> handler(Response.ok))

  def spec = suite("Traced")(
    test("a request in a trace is answered in a server span whose parent is the caller's") {
      val spans  = InMemorySpanExporter.create()
      val routes = answering @@ Traced.server("POST /screen", telemetry(spans))
      val trace  = "4bf92f3577b34da6a3ce929d0e0e4736"
      val asked  = Request
        .post(URL(Path.root / "screen"), Body.empty)
        .addHeader("traceparent", s"00-$trace-00f067aa0ba902b7-01")
      for response <- routes.runZIO(asked)
      yield
        val span = spans.getFinishedSpanItems.asScala.toList
        assertTrue(
          response.status == Status.Ok,
          span.size == 1,
          span.head.getName == "POST /screen",
          span.head.getKind == SpanKind.SERVER,
          span.head.getTraceId == trace,
          span.head.getParentSpanId == "00f067aa0ba902b7"
        )
    },
    test("a request with no trace starts one of its own") {
      val spans  = InMemorySpanExporter.create()
      val routes = answering @@ Traced.server("POST /screen", telemetry(spans))
      for _ <- routes.runZIO(Request.post(URL(Path.root / "screen"), Body.empty))
      yield
        val span = spans.getFinishedSpanItems.asScala.toList
        assertTrue(span.size == 1, !span.head.getParentSpanContext.isValid)
    }
  )
