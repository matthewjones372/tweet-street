package bank.access.sync

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/** access-sync as it runs in the cluster: everything from the environment, until it is stopped. */
fun main() {
    fun env(name: String) = System.getenv(name)?.takeIf(String::isNotEmpty)
    fun need(name: String) = env(name) ?: error("$name is not set")
    // The level Estate's debug switch writes to the access-sync-logging ConfigMap, mounted here (lark-bank spec 0026).
    env("LOG_LEVEL_FILE")?.let { LevelFile(Path.of(it)).follow(10.seconds) }
    val settings = SyncSettings(
        kafka = KafkaSettings(need("KAFKA_BOOTSTRAP"), env("KAFKA_GROUP") ?: "access-sync", env("KAFKA_USERNAME"), env("KAFKA_PASSWORD")),
        fgaUrl = need("FGA_API_URL"),
        fgaToken = need("FGA_API_TOKEN"),
        store = env("FGA_STORE") ?: "bank",
        pocketIdUrl = env("POCKET_ID_URL"),
        pocketIdKey = env("POCKET_ID_KEY"),
        groupsEvery = env("GROUPS_EVERY")?.let(Duration::parse) ?: Duration.ofMinutes(1),
        fromStart = env("FROM_START") == "true",
    )
    // TELEMETRY_ENABLED=false keeps the spans in, as compose does when Tempo is not up.
    val otlp = env("OTEL_EXPORTER_OTLP_ENDPOINT")?.takeIf { env("TELEMETRY_ENABLED") != "false" }
    val telemetry = openTelemetry(TraceSettings(otlp, env("TRACES_SAMPLED")?.toDouble() ?: 0.1))
    val done = CountDownLatch(1)
    // Closed last, so the spans of what access-sync did before it stopped are sent.
    telemetry.use {
        AccessSync(settings, telemetry).use { sync ->
            Runtime.getRuntime().addShutdownHook(Thread { done.countDown() })
            sync.start()
            done.await()
        }
    }
}
