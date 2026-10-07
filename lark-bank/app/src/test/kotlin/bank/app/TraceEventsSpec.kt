package bank.app

import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** Bank spec 0024's `trace-events`: an event is published in the trace it was written in, for a consumer to continue. */
class TraceEventsSpec {
    private val run = UUID.randomUUID().toString().take(8)

    /** The `traceparent` the first record keyed [key] on [topic] carries, or null if it carries none. */
    private fun traceparentOf(topic: String, key: String): String? {
        val properties = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to TestKafka.bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
        return KafkaConsumer(properties, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (System.nanoTime() < deadline) {
                val record = consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == key }
                if (record != null) return@use record.headers().lastHeader("traceparent")?.value()?.let { String(it) }
            }
            error("no record keyed $key on $topic")
        }
    }

    @Test
    fun `an account opened inside a trace is published with it, and its log annotations are not`() {
        TestCluster(1, extra = TestKafka.config).running { (node) ->
            val account = "traced-$run"
            val trace = UUID.randomUUID().toString().replace("-", "")
            val status = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${node.server.baseUrl}/accounts/$account"))
                    .header("Authorization", "Bearer ${TestIdentity.token("ada")}")
                    .header("Content-Type", "application/json")
                    .header("traceparent", "00-$trace-00f067aa0ba902b7-01")
                    .PUT(HttpRequest.BodyPublishers.ofString("""{"currency":"GBP","initial":"0"}"""))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
            status shouldBe 201

            val traceparent = traceparentOf(EventStream.ACCOUNTS.topic, account)
            traceparent?.split("-")?.get(1) shouldBe trace
        }
    }
}
