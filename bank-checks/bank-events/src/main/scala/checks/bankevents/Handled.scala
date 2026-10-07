package checks.bankevents

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import org.apache.kafka.clients.consumer.ConsumerRecord
import zio.*

import java.lang.Iterable as JavaIterable
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/**
 * A record of the bank's handled in the trace it was published in (lark-bank
 * spec 0024): a consumer span, child of the publish, whose ids are on every
 * line logged while it is handled, as `trace_id` and `span_id`. A record
 * published outside any trace is handled as before, in none.
 */
object Handled:
  private object Headers extends TextMapGetter[ConsumerRecord[?, ?]]:
    def keys(record: ConsumerRecord[?, ?]): JavaIterable[String] = record.headers.asScala.map(_.key).asJava
    def get(record: ConsumerRecord[?, ?], key: String): String   =
      Option(record).flatMap(r => Option(r.headers.lastHeader(key))).map(h => String(h.value, UTF_8)).orNull

  private val Destination = AttributeKey.stringKey("messaging.destination.name")

  def inTrace[R, E, A](telemetry: OpenTelemetry, name: String, record: ConsumerRecord[?, ?])(
    work: ZIO[R, E, A]
  ): ZIO[R, E, A] =
    if record.headers.lastHeader("traceparent") == null then work
    else
      ZIO.suspendSucceed {
        val parent = telemetry.getPropagators.getTextMapPropagator.extract(Context.root(), record, Headers)
        val span   = telemetry
          .getTracer("bank-checks")
          .spanBuilder(name)
          .setSpanKind(SpanKind.CONSUMER)
          .setParent(parent)
          .setAttribute(Destination, record.topic)
          .startSpan()
        val ids = span.getSpanContext
        ZIO
          .logAnnotate(LogAnnotation("trace_id", ids.getTraceId), LogAnnotation("span_id", ids.getSpanId))(work)
          .tapErrorCause(_ => ZIO.succeed(span.setStatus(StatusCode.ERROR): Unit))
          .ensuring(ZIO.succeed(span.end()))
      }
