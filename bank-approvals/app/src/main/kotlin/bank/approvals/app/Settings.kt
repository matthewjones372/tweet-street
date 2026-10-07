package bank.approvals.app

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.app.typesafe.loadedConfig
import kotlin.time.Duration

data class WebSettings(val host: String, val port: Int)

data class EntitySettings(val shards: Int, val passivateAfter: Duration, val askTimeout: Duration, val applyEvery: Duration)

data class DatabaseSettings(val url: String, val user: String, val password: String, val poolSize: Int)

/** Where `policies.yaml` is: shipped by Flux beside the service, read again when it changes. */
data class PolicySettings(val path: String)

/**
 * Where every request's events are published: the brokers, Apicurio's API (…/apis/registry/v3), copies of each
 * partition, and how many [publishers] follow the journal, each looking for more [every] so long.
 */
data class KafkaSettings(
    val bootstrap: String,
    val registry: String,
    val replication: Short,
    val publishers: Int,
    val every: Duration,
    /** Approvals' user on the brokers (lark-bank spec 0021), signed in with SCRAM-SHA-512; null connects as nobody. */
    val username: String? = null,
    val password: String? = null,
) {
    /** Every client's connection: the brokers, and how to sign in to them. */
    val client: Map<String, Any>
        get() = mapOf<String, Any>("bootstrap.servers" to bootstrap) + (username?.let { user ->
            mapOf(
                "security.protocol" to "SASL_PLAINTEXT",
                "sasl.mechanism" to "SCRAM-SHA-512",
                "sasl.jaas.config" to "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                    "username=\"${user.quoted()}\" password=\"${password.orEmpty().quoted()}\";",
            )
        } ?: emptyMap())

    private fun String.quoted() = replace("\"", "\\\"")
}

/** How often the nightly digest looks for a day to digest: each day, once over, is digested once. */
data class AuditSettings(val digestEvery: Duration)

/**
 * Whose tokens approvals takes, and for which audience (lark-bank spec 0021); and the pages' sign-in as the provider's
 * client: its id and secret, where the provider sends people back, and the key every node seals sessions with.
 */
data class IdentitySettings(
    val issuer: String,
    val audience: String,
    val clientId: String,
    val clientSecret: String,
    val callbackUrl: String,
    val sessionKey: String,
    /** The provider's clients that are owning services: a token issued to one is that service, in `services`. */
    val services: List<String> = emptyList(),
)

/**
 * Spans exported (lark-bank spec 0024): with [enabled], over OTLP/HTTP to [otlp], `/v1/traces` added; a request with no
 * trace of its own is sampled at [sampled], and one that arrives in a trace follows it.
 */
data class TelemetrySettings(val enabled: Boolean, val otlp: String, val sampled: Double)

val settings: Module =
    loadedConfig() +
        config<TelemetrySettings>("approvals.telemetry") {
            TelemetrySettings(boolean("enabled"), string("otlp"), of(0.0) { getDouble("sampled") })
        } +
        config<WebSettings>("approvals") { WebSettings(string("host"), int("port")) } +
        config<EntitySettings>("approvals.entities") {
            EntitySettings(int("shards"), duration("passivateAfter"), duration("askTimeout"), duration("applyEvery"))
        } +
        config<DatabaseSettings>("approvals.database") {
            DatabaseSettings(string("url"), string("user"), string("password"), int("poolSize"))
        } +
        config<PolicySettings>("approvals") { PolicySettings(string("policies")) } +
        config<KafkaSettings>("approvals.kafka") {
            KafkaSettings(
                string("bootstrap"), string("registry"), int("replication").toShort(), int("publishers"), duration("every"),
                string("username").ifEmpty { null }, string("password").ifEmpty { null },
            )
        } +
        config<AuditSettings>("approvals.audit") { AuditSettings(duration("digestEvery")) } +
        config<IdentitySettings>("approvals.identity") {
            IdentitySettings(
                string("issuer"), string("audience"), string("clientId"), string("clientSecret"), string("callbackUrl"), string("sessionKey"),
                string("services").split(',').map(String::trim).filter(String::isNotEmpty),
            )
        }
