package bank.app

import bank.domain.Currencies
import bank.domain.Currency
import bank.domain.Listed
import bank.domain.Money
import bank.domain.WhenUnanswered
import com.typesafe.config.Config
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.app.typesafe.loadedConfig
import kotlin.time.Duration

data class WebSettings(val host: String, val port: Int)

data class NodeSettings(val name: String, val host: String, val port: Int)

data class EntitySettings(
    val shards: Int,
    val passivateAfter: Duration,
    val snapshotEvery: Int,
    val askTimeout: Duration,
    val transferWait: Duration,
    val legTimeout: Duration,
    val batch: Int,
)

/**
 * How the read models run: in [partitions] partitions per journal database (spec 0014), each reading its share of
 * the slices, a batch of up to [batch] events or [within] at a time; and how often the sweeper looks for sagas stuck
 * for [stuckAfter].
 */
data class ReadModelSettings(
    val batch: Int,
    val within: Duration,
    val sweepEvery: Duration,
    val stuckAfter: Duration,
    val partitions: Int,
)

data class DatabaseSettings(val url: String, val user: String, val password: String, val poolSize: Int)

/**
 * The journal's databases (lark spec 0088): [primary] names `bank.database`, which also keeps the read models, and
 * [others] are the rest by name, with its user, password and pool size. The list is fixed once the bank holds data:
 * each database owns a range of slices worked out from how many there are, so adding one or reordering them moves
 * ids away from their events. With [groupCommit], each database's appends share commits (lark spec 0108).
 */
data class JournalSettings(val primary: String, val others: List<Pair<String, String>>, val groupCommit: Boolean)

/** `db-1=jdbc:postgresql://…,db-2=jdbc:postgresql://…` as named URLs, in order. */
private fun named(databases: String): List<Pair<String, String>> =
    databases.split(',').map(String::trim).filter(String::isNotEmpty).map { entry ->
        val (name, url) = entry.split('=', limit = 2)
        name.trim() to url.trim()
    }

/**
 * Where every event is published (bank spec 0015): the brokers, Apicurio's API (…/apis/registry/v3), and how many
 * copies the brokers keep of each topic's partitions. With a [username], the bank signs in to the brokers as it, with
 * SCRAM-SHA-512 (bank spec 0021); without, it connects as nobody, which only a broker with no users accepts.
 */
data class KafkaSettings(
    val enabled: Boolean,
    val bootstrap: String,
    val registry: String,
    val replication: Short,
    val username: String? = null,
    val password: String? = null,
) {
    /** Every client's connection: the brokers, and how to sign in to them. */
    val client: Map<String, Any>
        get() = mapOf<String, Any>("bootstrap.servers" to bootstrap) + (username?.let { user -> scram(user, password.orEmpty()) } ?: emptyMap())
}

/** SCRAM-SHA-512 over the brokers' client listener, as [user]. */
fun scram(user: String, password: String): Map<String, Any> = mapOf(
    "security.protocol" to "SASL_PLAINTEXT",
    "sasl.mechanism" to "SCRAM-SHA-512",
    "sasl.jaas.config" to "org.apache.kafka.common.security.scram.ScramLoginModule required " +
        "username=\"${user.replace("\"", "\\\"")}\" password=\"${password.replace("\"", "\\\"")}\";",
)

/**
 * Screening (bank spec 0018): off, a transfer debits as soon as it is requested; on, it asks the check at [url] first,
 * waits [timeout] for it, and does as [whenUnanswered] says if the check has not answered by then.
 */
data class ScreeningSettings(val enabled: Boolean, val url: String, val timeout: Duration, val whenUnanswered: WhenUnanswered)

/**
 * Who is calling (bank spec 0021): tokens from [issuer] for [audience], and people signing in to the pages as the client
 * [clientId], coming back to [callbackUrl]. [sessionKey] is the 32-byte key every node seals sessions with; empty makes
 * one at start, which signs people out of every other node.
 */
data class IdentitySettings(
    val issuer: String,
    val audience: String,
    val clientId: String,
    val clientSecret: String?,
    val callbackUrl: String,
    val sessionKey: String,
)

/**
 * Support's grants (bank spec 0021): with [enabled], the bank reads approval events from `bank.kafka.bootstrap` and
 * tells Approvals at [approvalsUrl] each grant it applied, with a token from [tokenUrl] for [tokenForm], and
 * [clientSecret] added when there is one. Off, support sees nothing but their own.
 */
data class AccessSettings(
    val enabled: Boolean,
    val approvalsUrl: String,
    val tokenUrl: String,
    val tokenForm: String,
    val clientSecret: String?,
)

/**
 * bank-access asked beside the bank's own rules (bank spec 0022's access-shadow): with [enabled], every viewer, payer
 * and acting question goes to OpenFGA at [url] too, as the store [store], with the preshared [token], and a
 * disagreement still there [recheckAfter] later is counted. The bank's own answer is always the one used.
 */
data class AccessShadowSettings(
    val enabled: Boolean,
    val url: String,
    val token: String,
    val store: String,
    val recheckAfter: Duration,
)

/**
 * Spans exported (bank spec 0024): with [enabled], over OTLP/HTTP to [otlp], `/v1/traces` added; a request the node takes
 * is sampled at [sampled], from 0 to 1, and every hop after follows it. Off, spans are still made, and dropped.
 */
data class TelemetrySettings(val enabled: Boolean, val otlp: String, val sampled: Double)

/**
 * The currencies the bank keeps (bank spec 0011): `bank.currencies.GBP { exponent = 2, symbol = "£" }`, with
 * `kind = crypto` for crypto.
 */
data class CurrencySettings(val currencies: Currencies)

private fun Config.currencies(): CurrencySettings {
    val entries = root().keys.sorted().map { code ->
        val entry = getConfig(code)
        val currency = Currency(code, entry.getInt("exponent"))
        val kind = if (entry.hasPath("kind")) Currency.Kind.valueOf(entry.getString("kind").replaceFirstChar(Char::uppercase))
        else Currency.Kind.Fiat
        Listed(currency, entry.getString("symbol"), kind)
    }
    return CurrencySettings(Currencies(entries))
}

val settings: Module =
    loadedConfig() +
        config<WebSettings>("bank") { WebSettings(string("host"), int("port")) } +
        config<NodeSettings>("bank.cluster.node") { NodeSettings(string("name"), string("host"), int("port")) } +
        config<EntitySettings>("bank.entities") {
            EntitySettings(
                int("shards"), duration("passivateAfter"), int("snapshotEvery"),
                duration("askTimeout"), duration("transferWait"), duration("legTimeout"), int("batch"),
            )
        } +
        config<ReadModelSettings>("bank.readModels") {
            ReadModelSettings(int("batch"), duration("within"), duration("sweepEvery"), duration("stuckAfter"), int("partitions"))
        } +
        config<DatabaseSettings>("bank.database") {
            DatabaseSettings(string("url"), string("user"), string("password"), int("poolSize"))
        } +
        config<JournalSettings>("bank.journal") {
            JournalSettings(string("primary"), named(string("databases")), boolean("groupCommit"))
        } +
        config<KafkaSettings>("bank.kafka") {
            KafkaSettings(
                boolean("enabled"), string("bootstrap"), string("registry"), int("replication").toShort(),
                string("username").ifEmpty { null }, string("password").ifEmpty { null },
            )
        } +
        config<ScreeningSettings>("bank.screening") {
            ScreeningSettings(
                boolean("enabled"), string("url"), duration("timeout"),
                WhenUnanswered.valueOf(string("whenUnanswered").replaceFirstChar(Char::uppercase)),
            )
        } +
        config<IdentitySettings>("bank.identity") {
            IdentitySettings(
                string("issuer"), string("audience"), string("clientId"), string("clientSecret").ifEmpty { null },
                string("callbackUrl"), string("sessionKey"),
            )
        } +
        config<AccessSettings>("bank.access") {
            AccessSettings(
                boolean("enabled"), string("approvalsUrl"), string("tokenUrl"), string("tokenForm"),
                string("clientSecret").ifEmpty { null },
            )
        } +
        config<AccessShadowSettings>("bank.accessShadow") {
            AccessShadowSettings(boolean("enabled"), string("url"), string("token"), string("store"), duration("recheckAfter"))
        } +
        config<TelemetrySettings>("bank.telemetry") {
            TelemetrySettings(boolean("enabled"), string("otlp"), of(0.0) { getDouble("sampled") })
        } +
        config<CurrencySettings>("bank") { of(CurrencySettings(Currencies(emptyList()))) { getConfig("currencies").currencies() } } +
        single { settings: CurrencySettings -> settings.currencies }
