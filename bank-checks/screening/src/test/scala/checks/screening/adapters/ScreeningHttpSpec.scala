package checks.screening.adapters

import checks.platform.TestPostgres
import checks.policy.domain.{LiveRule, LiveRules, Severity, Subject}
import checks.policy.service.LiveRulesSource
import checks.screening.service.*
import verdict.{FieldPath, Rule}
import zio.*
import zio.http.*
import zio.test.*

object ScreeningHttpSpec extends ZIOSpecDefault:
  private val large =
    LiveRules(
      Subject.Transfers,
      List(LiveRule("large-transfer", 3, Severity.High, 0, Rule.Gte(FieldPath.unsafe("amount"), 1000)))
    )

  // As lark-bank's generated ScreeningClient writes it.
  private def body(transfer: String, amount: String) =
    s"""{"transfer":"$transfer","from":"acc-1","to":"acc-2","amount":{"value":"$amount","currency":"GBP"},"requestedAtMillis":1700000000000}"""

  private def serve(live: LiveRules) =
    for
      source <- ZIO.succeed(live)
      ref    <- Ref.make(source)
      rules   = new LiveRulesSource:
                def live(subject: Subject) = ref.get
      port <- Server
                .install(ScreeningHttp.routes)
                .provideSomeLayer[Server & Decisions](ZLayer.succeed(rules) >>> Screening.layer.fresh)
                .provideSomeLayer[Server & javax.sql.DataSource](PostgresDecisions.layer.orDie)
    yield (s"http://127.0.0.1:$port/screen", ref)

  private def post(url: String, json: String) =
    ZIO.scoped(
      Client
        .batched(Request.post(url, Body.fromString(json)))
        .flatMap(response => response.body.asString.map(response.status -> _))
    )

  def spec = suite("POST /screen")(
    test("a transfer over the bound is declined by the rule, in the bank's words") {
      for
        (url, _)       <- serve(large)
        (status, text) <- post(url, body("t-large", "2500.00"))
      yield assertTrue(
        status == Status.Ok,
        text.contains("\"outcome\":\"declined\""),
        text.contains("\"rule\":\"large-transfer\""),
        text.contains("\"version\":3")
      )
    },
    test("a second request for a transfer answers the first decision, though the rules have changed") {
      for
        (url, rules) <- serve(large)
        (_, first)   <- post(url, body("t-again", "2500.00"))
        _            <- rules.set(LiveRules(Subject.Transfers, Nil))
        (_, second)  <- post(url, body("t-again", "2500.00"))
        (_, other)   <- post(url, body("t-other", "2500.00"))
      yield assertTrue(first == second, second.contains("declined"), other.contains("approved"))
    },
    test("an amount that is not a number is refused") {
      for
        (url, _)    <- serve(large)
        (status, _) <- post(url, body("t-bad", "lots"))
      yield assertTrue(status == Status.UnprocessableEntity)
    },
    test("p99 over 10,000 requests is under 20 ms") {
      for
        (url, _) <- serve(large)
        _        <- ZIO.foreachParDiscard(1 to 1000)(n => post(url, body(s"warm-$n", "10.00"))).withParallelism(8)
        timings  <- ZIO
                     .foreachPar(1 to 10000) { n =>
                       post(url, body(s"p-$n", if n % 4 == 0 then "2500.00" else "10.00")).timed.map(_._1.toNanos)
                     }
                     .withParallelism(8)
        sorted = timings.sorted
        p99    = sorted((sorted.size * 0.99).toInt) / 1_000_000.0
        _     <- ZIO.logInfo(f"screening p50 ${sorted(sorted.size / 2) / 1_000_000.0}%.2f ms, p99 $p99%.2f ms")
      yield assertTrue(p99 < 20.0)
    } @@ TestAspect.withLiveClock
  ).provideShared(
    TestPostgres.migrated("screening").orDie,
    Server.defaultWith(_.onAnyOpenPort),
    Client.default
  ) @@ TestAspect.sequential
