package checks.policy.adapters

import checks.platform.TestPostgres
import checks.policy.domain.*
import checks.policy.service.*
import zio.*
import zio.test.*

object ApprovingSpec extends ZIOSpecDefault:
  // Approvals as a test needs it: each ask answered with the next id and the hash it would make, every reply kept.
  final case class Heard(asked: Ref[List[Proposal]], told: Ref[List[String]]) extends Approvals:
    def ask(proposal: Proposal) =
      asked.modify(all => (s"req-${all.size + 1}" -> proposal.contentHash, all :+ proposal))
    def applied(request: String, hash: String)    = told.update(_ :+ s"applied $request")
    def applyFailed(request: String, why: String) = told.update(_ :+ s"failed $request")

  private val heard = ZLayer(Ref.make(List.empty[Proposal]).zipWith(Ref.make(List.empty[String]))(Heard.apply))

  // One Heard, which the policy asks and the tests read.
  private val everything =
    ZLayer.makeSome[javax.sql.DataSource, Heard & Policy](
      heard,
      ZLayer.succeed(PolicyConfig(1.hour, asking = true, link = Some("https://checks.example.internal"))),
      PostgresVersions.layer,
      Policy.layer
    )

  private val large = """{"op":"gte","path":"amount","value":1000}"""
  private val lower = """{"op":"gte","path":"amount","value":500}"""

  private def draft(rule: String, status: Status, document: String = large, severity: Severity = Severity.High) =
    Draft(rule, Subject.Transfers, document, severity, 1, status)

  private def live(rule: String) =
    ZIO
      .serviceWithZIO[Policy](_.all)
      .map(LiveRules.of(Subject.Transfers, _).rules.filter(_.name == rule).map(_.version))

  def spec = suite("Asking Approvals")(
    test("a version made live waits, asked about with what it changes, and is in force only once approval is given") {
      for
        h      <- ZIO.service[Heard]
        first  <- ZIO.serviceWithZIO[Policy](_.propose(draft("a", Status.Live), "ada", Some(Reach(0, 7, 100, 7))))
        before <- live("a")
        hash   <- h.asked.get.map(_.head.contentHash)
        _      <- ZIO.serviceWithZIO[Policy](_.decided(Decision.Given("req-1", hash)))
        after  <- live("a")
        told   <- h.told.get
        asked  <- h.asked.get
      yield assertTrue(
        first.approval == Approval.Waiting("req-1", asked.head.contentHash),
        before.isEmpty,
        after == List(1),
        told == List("applied req-1"),
        asked.head.title == "a, version 1",
        asked.head.subject == "checks/rule/a",
        asked.head.before == "(no version in force)",
        asked.head.after == "declines a transfer when amount is at least 1000\nseverity: high\nposition: 1",
        asked.head.facts == Map("change" -> "tighten", "severity" -> "high"),
        asked.head.impact.contains("declined 0 of 100 in the last 7 days; would have declined 7"),
        asked.head.link.contains("https://checks.example.internal/#/rules/a"),
        asked.head.requester == "ada"
      )
    },
    test("a refused version is never in force, and the one before it stays") {
      for
        h      <- ZIO.service[Heard]
        _      <- ZIO.serviceWithZIO[Policy](_.propose(draft("b", Status.Live), "ada", Some(Reach(0, 7, 100, 7))))
        hash   <- h.asked.get.map(_.last.contentHash)
        _      <- ZIO.serviceWithZIO[Policy](_.decided(Decision.Given("req-2", hash)))
        second <-
          ZIO.serviceWithZIO[Policy](_.propose(draft("b", Status.Live, lower), "ada", Some(Reach(7, 31, 100, 7))))
        _      <- ZIO.serviceWithZIO[Policy](_.decided(Decision.Ended("req-3", "rejected")))
        still  <- live("b")
        stored <- ZIO.serviceWithZIO[Policy](_.versions("b"))
        asked  <- h.asked.get
      yield assertTrue(
        second.approval.isInstanceOf[Approval.Waiting],
        still == List(1),
        stored.last.approval == Approval.Refused("req-3", "rejected"),
        asked.last.before.contains("at least 1000"),
        asked.last.after.contains("at least 500"),
        asked.last.facts("change") == "tighten"
      )
    },
    test("an approval of other content is refused, and one heard twice is answered twice and applied once") {
      for
        h     <- ZIO.service[Heard]
        _     <- ZIO.serviceWithZIO[Policy](_.propose(draft("c", Status.Live), "ada", None))
        hash  <- h.asked.get.map(_.last.contentHash)
        _     <- ZIO.serviceWithZIO[Policy](_.decided(Decision.Given("req-4", "not-the-hash")))
        wrong <- live("c")
        _     <- ZIO.serviceWithZIO[Policy](_.decided(Decision.Given("req-4", hash)))
        _     <- ZIO.serviceWithZIO[Policy](_.decided(Decision.Given("req-4", hash)))
        right <- live("c")
        told  <- h.told.get
      yield assertTrue(
        wrong.isEmpty,
        right == List(1),
        told.takeRight(3) == List("failed req-4", "applied req-4", "applied req-4")
      )
    },
    test("switching a low rule off is asked as a switch-off, and a draft is stored without asking") {
      for
        h     <- ZIO.service[Heard]
        _     <- ZIO.serviceWithZIO[Policy](_.propose(draft("d", Status.Live, severity = Severity.Low), "ada", None))
        hash  <- h.asked.get.map(_.last.contentHash)
        _     <- ZIO.serviceWithZIO[Policy](_.decided(Decision.Given("req-5", hash)))
        _     <- ZIO.serviceWithZIO[Policy](_.propose(draft("d", Status.Off, severity = Severity.Low), "bob", None))
        off   <- h.asked.get.map(_.last)
        count <- h.asked.get.map(_.size)
        draft <- ZIO.serviceWithZIO[Policy](_.propose(draft("e", Status.Draft), "ada", None))
        after <- h.asked.get.map(_.size)
      yield assertTrue(
        off.facts == Map("change" -> "switch-off", "severity" -> "low"),
        off.after == "(switched off)",
        off.title == "d, version 2, switched off",
        draft.approval == Approval.NotNeeded,
        after == count
      )
    },
    test("a first version tightens, whatever its reach; a new condition reaching as far reshapes") {
      for
        h       <- ZIO.service[Heard]
        _       <- ZIO.serviceWithZIO[Policy](_.propose(draft("f", Status.Live), "ada", Some(Reach(0, 0, 0, 7))))
        first   <- h.asked.get.map(_.last)
        asked   <- h.asked.get.map(_.size)
        _       <- ZIO.serviceWithZIO[Policy](_.decided(Decision.Given(s"req-$asked", first.contentHash)))
        _       <- ZIO.serviceWithZIO[Policy](_.propose(draft("f", Status.Live, lower), "ada", Some(Reach(3, 3, 9, 7))))
        reshape <- h.asked.get.map(_.last)
      yield assertTrue(first.facts("change") == "tighten", reshape.facts("change") == "reshape")
    }
  ).provideShared(TestPostgres.migrated("policy").orDie >>> everything.orDie) @@ TestAspect.sequential
