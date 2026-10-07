package checks.app

import checks.access.adapters.OidcConfig
import checks.access.service.AccessConfig
import checks.bankevents.AccountEventsConfig
import checks.monitoring.adapters.KafkaConfig
import checks.platform.TestPostgres
import checks.policy.service.PolicyConfig
import zio.*
import zio.test.*

object MigrationsSpec extends ZIOSpecDefault:
  private def settings =
    TestPostgres.fresh.map(database =>
      Settings(
        0,
        database,
        PolicyConfig(1.hour),
        KafkaSettings(false, KafkaConfig("", "", 1), AccountEventsConfig("", "", "")),
        AccessConfig(Set.empty, 1.hour, 10.minutes),
        OidcConfig("http://127.0.0.1:9", "checks-pages", "", "http://127.0.0.1/callback")
      )
    )

  def spec = suite("Migrations")(
    test("migrate applies every context's migrations once, and a second run applies none") {
      for
        settings <- settings
        first    <- Migrations.run(settings.database)
        second   <- Migrations.run(settings.database)
      yield assertTrue(
        first.keySet == Migrations.schemas.toSet,
        first.values.forall(_.nonEmpty),
        second.values.forall(_.isEmpty)
      )
    },
    test("the app refuses to start on a database migrate has not run on, naming what is pending") {
      for
        settings <- settings
        refused  <- Main.program(settings).timeout(30.seconds).flip
      yield assertTrue(
        refused.isInstanceOf[MigrationsPending],
        refused.getMessage.contains("policy 1 rule versions"),
        refused.getMessage.contains("access 1 sessions")
      )
    } @@ TestAspect.withLiveClock
  )
