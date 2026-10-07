package bank.access.sync

import bank.access.fga.TestOpenFga
import bank.events.v1.AccountEvent
import bank.events.v1.Money
import bank.events.v1.Opened
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Every span ended, kept for the test to read. */
private class Kept : SpanExporter {
    val spans = CopyOnWriteArrayList<SpanData>()

    override fun export(spans: Collection<SpanData>): CompletableResultCode = CompletableResultCode.ofSuccess().also { this.spans += spans }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/** Lark-bank spec 0024's `trace-events`: an account opened inside a trace has its owner written in that trace. */
class TracedSyncSpec {
    private val run = UUID.randomUUID().toString().take(8)

    @Test
    fun `an account published inside a trace has its owner written to OpenFGA in a span of that trace`() {
        TestKafka.ready()
        val fga = TestOpenFga.fresh()
        val kept = Kept()
        // Never sampled where it starts a trace: what is kept is kept because the publisher's trace was.
        val telemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().setSampler(Sampler.parentBased(Sampler.alwaysOff())).addSpanProcessor(SimpleSpanProcessor.create(kept)).build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()
        val acc = "traced-$run"
        val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
        val publish = "00f067aa0ba902b7"
        val settings = SyncSettings(
            kafka = KafkaSettings(TestKafka.bootstrap, group = "access-sync-$run"),
            fgaUrl = TestOpenFga.url, fgaToken = TestOpenFga.KEY, store = fga.store, pocketIdUrl = null, pocketIdKey = null,
        )
        telemetry.use {
            AccessSync(settings, telemetry).use { sync ->
                sync.start()
                val opened = AccountEvent.newBuilder().setAccountId(acc).setSequence(1).setAtMillis(System.currentTimeMillis())
                    .setOpened(Opened.newBuilder().setOwner("ada-$run").setInitial(Money.newBuilder().setCurrency("GBP").setAmount("0.00")).setReference("open:$acc"))
                    .build()
                TestKafka.send(ACCOUNTS, acc, opened, mapOf("traceparent" to "00-$trace-$publish-01"))
                TestKafka.send(ACCOUNTS, "untraced-$run", opened.toBuilder().setAccountId("untraced-$run").build())

                val until = System.nanoTime() + 60_000_000_000
                while (!fga.check("person:ada-$run", "owner", "account:untraced-$run")) {
                    check(System.nanoTime() < until) { "the untraced account was never written" }
                    Thread.sleep(200)
                }
            }
        }

        fga.check("person:ada-$run", "owner", "account:$acc") shouldBe true
        val span = kept.spans.single()
        span.name shouldBe "access-sync account"
        span.kind shouldBe SpanKind.CONSUMER
        span.traceId shouldBe trace
        span.parentSpanId shouldBe publish
    }
}
