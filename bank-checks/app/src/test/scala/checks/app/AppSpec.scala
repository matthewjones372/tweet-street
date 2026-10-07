package checks.app

import checks.access.adapters.OidcConfig
import checks.access.service.AccessConfig
import checks.bankevents.AccountEventsConfig
import checks.monitoring.adapters.KafkaConfig
import checks.platform.TestPostgres
import checks.policy.service.PolicyConfig
import zio.*
import zio.http.{Body, Client, Request, Server, URL}
import zio.test.*

object AppSpec extends ZIOSpecDefault:
  private def get(base: String, path: String) =
    ZIO.scoped(Client.batched(Request.get(URL.decode(base + path).toOption.get)).flatMap(_.body.asString))

  def spec = suite("The app")(
    test("it is healthy, describes /screen in its OpenAPI document, and measures each screening") {
      for
        database <- TestPostgres.fresh
        settings  = Settings(
                     0,
                     database,
                     PolicyConfig(1.hour),
                     KafkaSettings(false, KafkaConfig("", "", 1), AccountEventsConfig("", "", "")),
                     AccessConfig(Set.empty, 1.hour, 10.minutes),
                     OidcConfig("http://127.0.0.1:9", "checks-pages", "", "http://127.0.0.1/callback")
                   )
        _    <- Migrations.run(database)
        seen <- (for
                  port    <- Server.install(Main.routes)
                  base     = s"http://127.0.0.1:$port"
                  health  <- get(base, "/health")
                  openApi <- get(base, "/openapi.json")
                  _       <- ZIO.scoped(
                         Client.batched(
                           Request.post(
                             URL.decode(s"$base/screen").toOption.get,
                             Body.fromString(
                               """{"transfer":"t","from":"a","to":"b","amount":{"value":"1.00","currency":"GBP"},"requestedAtMillis":0}"""
                             )
                           )
                         )
                       )
                  metrics <-
                    get(base, "/metrics").repeatUntil(_.contains("checks_screening_duration_ms")).timeout(15.seconds)
                yield (health, openApi, metrics)).provide(Main.layers(settings), Client.default)
        (health, openApi, metrics) = seen
      yield assertTrue(
        health == "ok",
        openApi.contains("\"/screen\""),
        metrics.exists(_.contains("checks_screening_decisions"))
      )
    } @@ TestAspect.withLiveClock
  )
