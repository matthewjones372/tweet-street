package checks.bankevents

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import org.apache.kafka.clients.consumer.ConsumerRecord
import zio.*
import zio.test.*

import scala.jdk.CollectionConverters.*

/**
 * lark-bank spec 0024's `trace-events`, monitoring's half: an event published
 * in a trace is checked in it.
 */
object HandledSpec extends ZIOSpecDefault:
  private def telemetry(spans: InMemorySpanExporter) = OpenTelemetrySdk
    .builder()
    .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
    .build()

  private def record(traceparent: Option[String]) =
    val made = ConsumerRecord[String, Array[Byte]](AccountEvents.topic, 0, 0L, "acc-1", Array.emptyByteArray)
    traceparent.foreach(value => made.headers.add("traceparent", value.getBytes("UTF-8")))
    made

  // The trace id on the line written while the record is handled.
  private val logged = ZIO.logAnnotations.map(_.get("trace_id"))

  def spec = suite("Handled")(
    test("an event published in a trace is handled in a consumer span whose parent is the publish") {
      val spans = InMemorySpanExporter.create()
      val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
      for heard <- Handled.inTrace(
                     telemetry(spans),
                     "bank-checks monitoring",
                     record(Some(s"00-$trace-00f067aa0ba902b7-01"))
                   )(logged)
      yield
        val span = spans.getFinishedSpanItems.asScala.toList
        assertTrue(
          heard.contains(trace),
          span.size == 1,
          span.head.getName == "bank-checks monitoring",
          span.head.getKind == SpanKind.CONSUMER,
          span.head.getTraceId == trace,
          span.head.getParentSpanId == "00f067aa0ba902b7"
        )
    },
    test("an event published in no trace is handled in none") {
      val spans = InMemorySpanExporter.create()
      for heard <- Handled.inTrace(telemetry(spans), "bank-checks monitoring", record(None))(logged)
      yield assertTrue(heard.isEmpty, spans.getFinishedSpanItems.isEmpty)
    }
  )
