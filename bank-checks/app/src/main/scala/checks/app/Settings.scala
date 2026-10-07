package checks.app

import checks.access.adapters.OidcConfig
import checks.access.service.AccessConfig
import checks.bankevents.AccountEventsConfig
import checks.monitoring.adapters.KafkaConfig
import checks.platform.{DatabaseConfig, KafkaSignIn}
import checks.policy.adapters.ApprovalsConfig
import checks.policy.service.PolicyConfig
import com.typesafe.config.{Config, ConfigFactory}
import zio.*

final case class KafkaSettings(enabled: Boolean, kafka: KafkaConfig, consumer: AccountEventsConfig)

/**
 * Spans to Tempo (lark-bank spec 0024): with [[enabled]], over OTLP/HTTP to
 * [[otlp]]; a request with no trace of its own is sampled at [[sampled]], and
 * one that arrives in a trace follows it.
 */
final case class TelemetrySettings(
  enabled: Boolean = false,
  otlp: String = "http://127.0.0.1:4318",
  sampled: Double = 0.1
)

final case class Settings(
  port: Int,
  database: DatabaseConfig,
  policy: PolicyConfig,
  kafka: KafkaSettings,
  access: AccessConfig,
  identity: OidcConfig,
  approvals: Option[ApprovalsConfig] = None,
  telemetry: TelemetrySettings = TelemetrySettings()
)

object Settings:
  private def duration(config: Config, path: String) = Duration.fromJava(config.getDuration(path))

  def groups(text: String): Set[String] = text.split(',').map(_.trim).filter(_.nonEmpty).toSet

  def from(root: Config): Settings =
    val config = root.getConfig("checks")
    val kafka  = config.getConfig("kafka")
    val signIn = KafkaSignIn(Some(kafka.getString("username")).filter(_.nonEmpty), kafka.getString("password"))
    Settings(
      port = config.getInt("port"),
      database = DatabaseConfig(
        config.getString("database.url"),
        config.getString("database.user"),
        config.getString("database.password"),
        config.getInt("database.poolSize")
      ),
      policy = PolicyConfig(
        duration(config, "policy.pollEvery"),
        asking = config.getBoolean("approvals.enabled"),
        link = Some(config.getString("publicUrl")).filter(_.nonEmpty),
        approvalsPages = Some(config.getString("approvals.pages")).filter(_.nonEmpty)
      ),
      kafka = KafkaSettings(
        kafka.getBoolean("enabled"),
        KafkaConfig(
          kafka.getString("bootstrap"),
          kafka.getString("registry"),
          kafka.getInt("replication").toShort,
          signIn
        ),
        AccountEventsConfig(kafka.getString("bootstrap"), kafka.getString("registry"), kafka.getString("group"), signIn)
      ),
      access = AccessConfig(
        groups(config.getString("admins")),
        duration(config, "sessionFor"),
        duration(config, "signInWithin")
      ),
      identity = OidcConfig(
        config.getString("identity.issuer"),
        config.getString("identity.clientId"),
        config.getString("identity.clientSecret"),
        config.getString("identity.callbackUrl")
      ),
      telemetry = TelemetrySettings(
        config.getBoolean("telemetry.enabled"),
        config.getString("telemetry.otlp"),
        config.getDouble("telemetry.sampled")
      ),
      approvals = Option.when(config.getBoolean("approvals.enabled"))(
        ApprovalsConfig(
          config.getString("approvals.url"),
          config.getString("approvals.tokenUrl"),
          config.getString("approvals.tokenForm"),
          config.getString("approvals.clientSecret")
        )
      )
    )

  val load: Task[Settings] = ZIO.attempt(from(ConfigFactory.load()))
