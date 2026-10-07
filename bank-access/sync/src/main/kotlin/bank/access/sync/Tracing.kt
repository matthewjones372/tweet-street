package bank.access.sync

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.apache.kafka.clients.consumer.ConsumerRecord

/** Where access-sync's spans go, and what share of the traces it starts it keeps; with no [otlp], none leave. */
data class TraceSettings(val otlp: String? = null, val sampled: Double = 0.1)

/** The SDK as the estate's services build it (lark-bank spec 0024): W3C trace context, following the caller's choice. */
fun openTelemetry(settings: TraceSettings): OpenTelemetrySdk {
    val tracing = SdkTracerProvider.builder()
        .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "access-sync"))))
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(settings.sampled)))
        .apply {
            settings.otlp?.let { otlp ->
                addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder().setEndpoint(otlp.trimEnd('/') + "/v1/traces").build()).build())
            }
        }
        .build()
    return OpenTelemetrySdk.builder()
        .setTracerProvider(tracing)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
}

/** The trace a record was published in, from its headers; the current context where it carries none. */
private fun OpenTelemetry.publishedIn(record: ConsumerRecord<String, ByteArray>): Context =
    propagators.textMapPropagator.extract(Context.current(), record, recordHeaders)

private val recordHeaders = object : TextMapGetter<ConsumerRecord<String, ByteArray>> {
    override fun keys(carrier: ConsumerRecord<String, ByteArray>) = carrier.headers().map { it.key() }

    override fun get(carrier: ConsumerRecord<String, ByteArray>?, key: String): String? =
        carrier?.headers()?.lastHeader(key)?.value()?.toString(Charsets.UTF_8)
}

/** Whether [record] was published inside a trace: those are handled one at a time, each in its own. */
fun traced(record: ConsumerRecord<String, ByteArray>) = record.headers().lastHeader("traceparent") != null

/** [work] in a consumer span named [name], a child of the trace [record] was published in. */
fun <T> OpenTelemetry.handling(name: String, record: ConsumerRecord<String, ByteArray>, work: () -> T): T {
    val span = getTracer("access-sync").spanBuilder(name).setSpanKind(SpanKind.CONSUMER).setParent(publishedIn(record))
        .setAttribute("messaging.destination.name", record.topic())
        .startSpan()
    return try {
        span.makeCurrent().use { work() }
    } catch (failed: Exception) {
        span.recordException(failed)
        throw failed
    } finally {
        span.end()
    }
}

/** The current trace as headers, for the calls made inside it: OpenFGA's spans join it. */
fun OpenTelemetry.headers(): Map<String, String> =
    mutableMapOf<String, String>().also { propagators.textMapPropagator.inject(Context.current(), it, mapSetter) }

private val mapSetter = TextMapSetter<MutableMap<String, String>> { carrier, key, value -> carrier?.put(key, value) }
