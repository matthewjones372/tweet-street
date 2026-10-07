package checks.admin

import checks.access.adapters.PostgresSessions
import checks.access.domain.{AccessError, Admin, Session, Signed}
import checks.access.service.{Access, AccessConfig, IdentityProvider, Sessions}
import checks.monitoring.adapters.PostgresMovements
import checks.monitoring.domain.{Flag, MonitoringError, Seen}
import checks.monitoring.service.{FlagSink, Monitoring, MonitoringQueries}
import checks.platform.TestPostgres
import checks.policy.adapters.PostgresVersions
import checks.policy.domain.{Movement, ProposedTransfer, Subject}
import checks.policy.service.{Approvals, Policy, PolicyConfig}
import checks.screening.adapters.PostgresDecisions
import checks.screening.domain.{Asked, Outcome}
import checks.screening.service.{Screening, ScreeningQueries}
import verdict.{Evaluator, RuleJson}
import zio.*
import zio.http.{Body, Client, Header, Request, Server, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.sql.DataSource

object AdminApiSpec extends ZIOSpecDefault:
  private val noFlags: ULayer[FlagSink] = ZLayer.succeed(new FlagSink:
    def publish(flags: List[Flag]): IO[MonitoringError, Unit] = ZIO.unit)

  // Signing in is AccessSpec's and WizardSpec's: these tests start signed in.
  private val noProvider: ULayer[IdentityProvider] = ZLayer.succeed(new IdentityProvider:
    def authorizeUrl(state: String, nonce: String, challenge: String): IO[AccessError, String] =
      ZIO.fail(AccessError.BadSignIn("not here"))
    def exchange(code: String, verifier: String, nonce: String): IO[AccessError, Signed] =
      ZIO.fail(AccessError.BadSignIn("not here")))

  private val policy: RLayer[DataSource, Policy] =
    ZLayer.succeed(PolicyConfig(1.hour)) ++ PostgresVersions.layer ++ Approvals.off >>> Policy.layer

  private val everything =
    ZLayer.makeSome[
      DataSource,
      Policy & Screening & ScreeningQueries & Monitoring & MonitoringQueries & Access & Sessions
    ](
      policy,
      PostgresDecisions.layer,
      Screening.layer,
      PostgresMovements.layer,
      noFlags,
      Monitoring.layer,
      ZLayer.succeed(AccessConfig(Set("risk"), 1.hour, 10.minutes)),
      noProvider,
      PostgresSessions.layer,
      Access.layer
    )

  private val transferRule =
    """{"op":"and","left":{"op":"gte","path":"amount","value":1000},"right":{"op":"not","rule":{"op":"eq","path":"currency","value":"BTC"}}}"""

  private val movementRule =
    """{"op":"and","left":{"op":"eq","path":"kind","value":"withdrawal"},"right":{"op":"gte","path":"amount","value":200}}"""

  private def seed =
    Clock
      .currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      .flatMap(now =>
        ZIO.foreachDiscard(1 to 40) { n =>
          Screening.screen(Asked(s"seed-$n", "a", "b", if n % 5 == 0 then "BTC" else "GBP", n * 100, 0)) *>
            Monitoring.check(
              Seen(
                s"acc-$n",
                n.toLong,
                now,
                Some(Movement(s"acc-$n", if n % 2 == 0 then "withdrawal" else "deposit", "GBP", n * 10, "r", 9))
              )
            )
        }
      )

  final case class Api(base: String, cookie: Option[String]):
    def call(method: String, path: String, json: String = ""): ZIO[Client, Throwable, (Status, String)] =
      val url     = URL.decode(base + path).toOption.get
      val request = (if method == "GET" then Request.get(url) else Request.post(url, Body.fromString(json)))
        .addHeaders(zio.http.Headers(cookie.toList.map(c => Header.Custom("Cookie", c))))
      ZIO.scoped(Client.batched(request).flatMap(r => r.body.asString.map(r.status -> _)))

  private def field(json: String, name: String): Json =
    json
      .fromJson[Json]
      .toOption
      .flatMap {
        case Json.Obj(fields) => fields.collectFirst { case (`name`, value) => value }
        case _                => None
      }
      .getOrElse(Json.Null)

  private def signIn(base: String) =
    for
      token <- ZIO.succeed(java.util.UUID.randomUUID.toString)
      _     <- ZIO.serviceWithZIO[Sessions](_.open(Session(token, Admin("ada"), Long.MaxValue)))
    yield Api(base, Some(s"${AdminApi.cookieName}=$token"))

  private def serve =
    Server.install(AdminApi.routes).map(port => s"http://127.0.0.1:$port")

  def spec = suite("The admin API")(
    test("a rule over transfers is drafted, read, tried, dry-run and made live, and then declines the next one") {
      for
        base    <- serve
        _       <- seed
        api     <- signIn(base)
        checked <-
          api.call("POST", "/rules/validate", s"""{"record":"transfer","document":${Json.Str(transferRule).toJson}}""")
        tried <- api.call(
                   "POST",
                   "/rules/try",
                   s"""{"record":"transfer","document":${Json.Str(transferRule).toJson},
                          "transfer":{"from":"a","to":"b","currency":"GBP","amount":2500,"hourOfDay":10}}"""
                 )
        dry <-
          api.call("POST", "/rules/dry-run", s"""{"record":"transfer","document":${Json.Str(transferRule).toJson}}""")
        records  <- ZIO.serviceWithZIO[ScreeningQueries](_.asked(0))
        rule      = RuleJson.parse(transferRule).toOption.get
        evaluated = records.count(r => Evaluator.holds(rule, r, ProposedTransfer.rules).contains(true))
        stored   <- api.call(
                    "POST",
                    "/rules/large-transfer/versions",
                    s"""{"record":"transfer","document":${Json
                        .Str(transferRule)
                        .toJson},"severity":"high","position":1,"status":"live"}"""
                  )
        listed <- api.call("GET", "/rules")
        _      <- ZIO.serviceWithZIO[Policy](_.live(Subject.Transfers)).repeatUntil(_.rules.nonEmpty).timeout(5.seconds)
        next   <- Screening.screen(Asked("after", "a", "b", "GBP", 5000, 0))
      yield assertTrue(
        checked._2.contains("amount is at least 1000"),
        field(tried._2, "held") == Json.Bool(true),
        field(dry._2, "matched") == Json.Num(evaluated),
        evaluated == 24,
        stored._1 == Status.Ok,
        field(stored._2, "number") == Json.Num(1),
        listed._2.contains("\"live\":1"),
        next.outcome == Outcome.Declined,
        next.rule.contains("large-transfer")
      )
    },
    test("a rule over movements is dry-run as the evaluator counts, and made live") {
      for
        base <- serve
        api  <- signIn(base)
        dry  <-
          api.call("POST", "/rules/dry-run", s"""{"record":"movement","document":${Json.Str(movementRule).toJson}}""")
        records  <- ZIO.serviceWithZIO[MonitoringQueries](_.movements(0))
        rule      = RuleJson.parse(movementRule).toOption.get
        evaluated = records.count(r => Evaluator.holds(rule, r, Movement.rules).contains(true))
        stored   <- api.call(
                    "POST",
                    "/rules/large-withdrawal/versions",
                    s"""{"record":"movement","document":${Json
                        .Str(movementRule)
                        .toJson},"severity":"medium","position":1,"status":"live"}"""
                  )
        one <- api.call("GET", "/rules/large-withdrawal")
      yield assertTrue(
        field(dry._2, "matched") == Json.Num(evaluated),
        evaluated == 11,
        stored._1 == Status.Ok,
        one._2.contains("kind is \\\"withdrawal\\\" and amount is at least 200")
      )
    },
    test("a draft naming a field its record lacks is checked, not stored") {
      for
        base    <- serve
        api     <- signIn(base)
        checked <- api.call(
                     "POST",
                     "/rules/validate",
                     """{"record":"transfer","document":"{\"op\":\"gt\",\"path\":\"amont\",\"value\":1}"}"""
                   )
        stored <- api.call(
                    "POST",
                    "/rules/typo/versions",
                    """{"record":"transfer","document":"{\"op\":\"gt\",\"path\":\"amont\",\"value\":1}","severity":"low","position":1,"status":"live"}"""
                  )
      yield assertTrue(checked._2.contains("no field 'amont'"), stored._1 == Status.UnprocessableEntity)
    },
    test("nothing is read or written without signing in") {
      for
        base   <- serve
        rules  <- Api(base, None).call("GET", "/rules")
        stored <- Api(base, None).call(
                    "POST",
                    "/rules/x/versions",
                    """{"record":"transfer","document":"{}","severity":"low","position":1,"status":"live"}"""
                  )
      yield assertTrue(rules._1 == Status.Unauthorized, stored._1 == Status.Unauthorized)
    }
  ).provideShared(
    TestPostgres.migrated("policy", "screening", "monitoring", "access").orDie >>> (everything ++ ZLayer
      .service[DataSource]).orDie,
    ZLayer.succeed(PolicyConfig(1.hour)),
    Server.defaultWith(_.onAnyOpenPort),
    Client.default
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
