package checks.app

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import zio.*

/**
 * The SDK the check's spans are made through (lark-bank spec 0024), closed, and
 * its last spans sent, as it stops.
 */
object Telemetry:
  private val ServiceName = AttributeKey.stringKey("service.name")

  def sdk(settings: TelemetrySettings): ZIO[Scope, Nothing, OpenTelemetry] =
    ZIO.acquireRelease(ZIO.succeed(build(settings)))(sdk => ZIO.succeed(sdk.close()))

  private def build(settings: TelemetrySettings): OpenTelemetrySdk =
    val tracing = SdkTracerProvider
      .builder()
      .setResource(Resource.getDefault.merge(Resource.create(Attributes.of(ServiceName, "bank-checks"))))
      .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(settings.sampled)))
    val exporting =
      if settings.enabled then
        val exporter = OtlpHttpSpanExporter.builder().setEndpoint(settings.otlp.stripSuffix("/") + "/v1/traces").build()
        tracing.addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
      else tracing
    OpenTelemetrySdk
      .builder()
      .setTracerProvider(exporting.build())
      .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
      .build()
