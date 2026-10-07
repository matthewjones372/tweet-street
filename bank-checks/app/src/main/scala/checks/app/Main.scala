package checks.app

import checks.access.adapters.{OidcProvider, PostgresSessions}
import checks.access.service.Access
import checks.admin.{AdminApi, Pages}
import checks.bankevents.AccountEvents
import checks.monitoring.adapters.{KafkaFlags, PostgresMovements}
import checks.monitoring.domain.{Flag, MonitoringError}
import checks.monitoring.service.{FlagSink, Monitoring, MonitoringQueries}
import checks.platform.Database
import checks.bankevents.ApprovalEvents
import checks.policy.adapters.{ApprovalsHttp, PostgresVersions}
import checks.policy.service.{Approvals, Policy, PolicyConfig}
import checks.screening.adapters.{PostgresDecisions, ScreeningHttp, Traced}
import checks.screening.service.{Screening, ScreeningQueries}
import io.opentelemetry.api.OpenTelemetry
import zio.*

import javax.sql.DataSource
import zio.http.*
import zio.http.endpoint.openapi.{OpenAPI, OpenAPIGen, SwaggerUI}
import zio.logging.backend.SLF4J
import zio.metrics.connectors.{MetricsConfig, prometheus}
import zio.metrics.connectors.prometheus.PrometheusPublisher

object Main extends ZIOAppDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  // zio-http names no operation; a generated client names its methods by them, and the bank's calls `screen`.
  private val operationIds = Map(
    "/screen"                -> "screen",
    "/schema/{record}"       -> "schema",
    "/rules"                 -> "rules",
    "/rules/{name}"          -> "rule",
    "/rules/validate"        -> "validate",
    "/rules/try"             -> "tryRule",
    "/rules/dry-run"         -> "dryRun",
    "/rules/{name}/versions" -> "newVersion",
    "/decisions"             -> "decisions",
    "/flags"                 -> "flags",
    "/me"                    -> "me",
    "/logout"                -> "signOut",
    "/screen/test"           -> "testTransfer"
  )

  val openApi =
    val generated = OpenAPIGen.fromEndpoints("bank-checks", "0.1.0", ScreeningHttp.screen :: AdminApi.endpoints)
    generated.copy(paths = generated.paths.map { (path, item) =>
      def named(operation: Option[OpenAPI.Operation]) =
        operation.map(_.copy(operationId = operationIds.get(path.name)))
      path -> item.copy(get = named(item.get), post = named(item.post))
    })

  /**
   * Every route, screening's traced through [[telemetry]] (lark-bank spec
   * 0024).
   */
  def routesWith(telemetry: OpenTelemetry) =
    (ScreeningHttp.routes @@ Traced.server(
      "POST /screen",
      telemetry
    )) ++ AdminApi.routes ++ AdminApi.signInRoutes ++ Pages.routes ++ SwaggerUI.routes(
      "docs",
      openApi
    ) ++ Routes(
      Method.GET / "health"       -> handler(Response.text("ok")),
      Method.GET / "openapi.json" -> handler(Response.json(openApi.toJson)),
      Method.GET / "metrics"      -> handler(ZIO.serviceWithZIO[PrometheusPublisher](_.get).map(Response.text))
    )

  /** The routes with no spans made, as a test serves them. */
  val routes = routesWith(OpenTelemetry.noop())

  // Off, flags have nowhere to go, and there are none: nothing reads the bank's events.
  private val noFlags: ULayer[FlagSink] = ZLayer.succeed(new FlagSink:
    def publish(flags: List[Flag]): IO[MonitoringError, Unit] = ZIO.unit)

  def layers(settings: Settings) =
    val flags = if settings.kafka.enabled then ZLayer.succeed(settings.kafka.kafka) >>> KafkaFlags.layer else noFlags
    // A version that would change what is in force is asked about in Approvals first (lark-bank spec 0019).
    val approvals =
      settings.approvals.fold(Approvals.off)(config =>
        (ZLayer.succeed(config) ++ Client.default.orDie) >>> ApprovalsHttp.layer
      )
    ZLayer.make[
      Policy & PolicyConfig & Screening & ScreeningQueries & Monitoring & MonitoringQueries & Access & Server &
        PrometheusPublisher
    ](
      ZLayer.succeed(settings.database) >>> Database.dataSource,
      ZLayer.succeed(settings.policy),
      PostgresVersions.layer,
      approvals,
      Policy.layer,
      PostgresDecisions.layer,
      Screening.layer,
      PostgresMovements.layer,
      flags,
      Monitoring.layer,
      ZLayer.succeed(settings.access),
      ZLayer.succeed(settings.identity),
      OidcProvider.layer,
      PostgresSessions.layer,
      Access.layer,
      Server.defaultWithPort(settings.port),
      ZLayer.succeed(MetricsConfig(5.seconds)),
      prometheus.publisherLayer,
      prometheus.prometheusLayer
    )

  // The bank's events are read until the process ends; a failure is logged and the read starts again.
  private def monitor(settings: Settings, telemetry: OpenTelemetry) =
    ZIO.when(settings.kafka.enabled)(
      AccountEvents
        .runWith(telemetry)
        .provideSome[Monitoring](
          ZLayer.succeed(settings.kafka.consumer),
          AccountEvents.consumer(settings.kafka.consumer)
        )
        .tapErrorCause(cause => ZIO.logWarningCause("reading bank.account-events failed; starting again", cause))
        .retry(Schedule.spaced(5.seconds))
        .forkDaemon
    )

  // Approvals' answers about the check's own requests, heard until the process ends, as the bank's events are.
  private def hearApprovals(settings: Settings) =
    ZIO.when(settings.kafka.enabled && settings.approvals.nonEmpty)(
      ApprovalEvents.run
        .provideSome[Policy](
          ZLayer.succeed(settings.kafka.consumer.copy(group = "checks-approvals")),
          AccountEvents.consumer(settings.kafka.consumer.copy(group = "checks-approvals"))
        )
        .tapErrorCause(cause => ZIO.logWarningCause("reading bank.approval-events failed; starting again", cause))
        .retry(Schedule.spaced(5.seconds))
        .forkDaemon
    )

  // The live rules load as the layers are built, so the migrations are checked before them.
  def program(settings: Settings) =
    ZIO.scoped {
      (ZLayer.succeed(settings.database) >>> Database.dataSource).build.flatMap(env => Migrations.requireNone(env.get))
    } *>
      ZIO.scoped {
        Telemetry.sdk(settings.telemetry).flatMap { telemetry =>
          monitor(settings, telemetry) *> hearApprovals(settings) *> ZIO.logInfo(s"bank-checks on ${settings.port}") *>
            Server.serve(routesWith(telemetry))
        }
      }.provide(layers(settings))

  def run = Settings.load.flatMap(program)
