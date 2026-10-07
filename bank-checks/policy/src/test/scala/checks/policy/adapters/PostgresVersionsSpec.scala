package checks.policy.adapters

import checks.platform.TestPostgres
import checks.policy.domain.*
import checks.policy.service.*
import zio.*
import zio.test.*

object PostgresVersionsSpec extends ZIOSpecDefault:
  private val large = """{"op":"gt","path":"amount","value":1000}"""

  private def draft(status: Status, rule: String = "large-transfer") =
    Draft(rule, Subject.Transfers, large, Severity.High, 1, status)

  // The poll is an hour, so only the store's notification can bring a change in within the test.
  private val policy =
    ZLayer.succeed(PolicyConfig(1.hour)) ++ PostgresVersions.layer ++ Approvals.off >>> Policy.layer

  def spec = suite("Postgres versions")(
    test("a version stored by one node is live on another within a second") {
      for
        dataSource <- ZIO.service[javax.sql.DataSource]
        env         = ZLayer.succeed(dataSource)
        reader     <- ZIO.scoped((env >>> policy).build.map(_.get[Policy]).flatMap { other =>
                    for
                      _     <- Policy.store(draft(Status.Live), "ada").provide(env >>> policy)
                      start <- Clock.nanoTime
                      _     <- other.live(Subject.Transfers).repeatUntil(_.rules.nonEmpty).timeout(1.second)
                      end   <- Clock.nanoTime
                      live  <- other.live(Subject.Transfers)
                    yield (live, (end - start) / 1_000_000)
                  })
        (live, millis) = reader
      yield assertTrue(live.rules.map(rule => rule.name -> rule.version) == List("large-transfer" -> 1), millis < 1000)
    } @@ TestAspect.withLiveClock,
    test("switching a rule off keeps its history") {
      for
        _        <- Policy.store(draft(Status.Live), "ada")
        _        <- Policy.store(draft(Status.Off), "bob")
        versions <- ZIO.serviceWithZIO[Policy](_.versions("large-transfer"))
        live     <- ZIO.serviceWithZIO[Policy](_.all).map(LiveRules.of(Subject.Transfers, _))
      yield assertTrue(
        versions.map(version => (version.number, version.status, version.author)) ==
          List((1, Status.Live, "ada"), (2, Status.Off, "bob")),
        live.rules.isEmpty
      )
    }.provideSome[javax.sql.DataSource](policy),
    test("a rule over transfers cannot become a rule over movements") {
      for
        _       <- Policy.store(draft(Status.Live), "ada")
        refused <- Policy.store(draft(Status.Live).copy(subject = Subject.Movements), "ada").flip
      yield assertTrue(refused.isInstanceOf[PolicyError.SubjectChanged] || refused.isInstanceOf[PolicyError.Invalid])
    }.provideSome[javax.sql.DataSource](policy)
  ).provideLayer(TestPostgres.migrated("policy").orDie) @@ TestAspect.sequential
