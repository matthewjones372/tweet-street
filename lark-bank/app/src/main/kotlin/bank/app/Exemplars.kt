package bank.app

import io.opentelemetry.api.trace.Span
import io.prometheus.metrics.tracer.common.SpanContext

/**
 * The span a meter is recorded in, as Prometheus' exemplars read it (bank spec 0024's `trace-views`): a latency
 * observed inside a sampled span keeps that span's trace id beside its bucket, so a panel's slow point opens its
 * trace. Nothing when no span is current, or it is not sampled.
 */
object CurrentSpan : SpanContext {
    private val span: io.opentelemetry.api.trace.SpanContext get() = Span.current().spanContext

    override fun getCurrentTraceId(): String? = span.takeIf { it.isValid }?.traceId

    override fun getCurrentSpanId(): String? = span.takeIf { it.isValid }?.spanId

    override fun isCurrentSpanSampled(): Boolean = span.isValid && span.isSampled

    override fun markCurrentSpanAsExemplar() {
        Span.current().setAttribute(SpanContext.EXEMPLAR_ATTRIBUTE_NAME, SpanContext.EXEMPLAR_ATTRIBUTE_VALUE)
    }
}

/** What Prometheus is answered with: OpenMetrics, the one exposition format that carries exemplars. */
const val OPENMETRICS = "application/openmetrics-text; version=1.0.0; charset=utf-8"
