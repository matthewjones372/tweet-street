package checks.policy.domain

import verdict.{FieldPath, Rule}
import zio.test.*

object PolicySpec extends ZIOSpecDefault:
  private def draft(document: String, subject: Subject = Subject.Transfers) =
    Draft("large-transfer", subject, document, Severity.High, 1, Status.Live)

  private def version(rule: String, number: Int, status: Status, position: Int = 1) =
    Version(
      rule,
      number,
      Subject.Transfers,
      Rule.Gt(FieldPath.unsafe("amount"), 1000),
      Severity.High,
      position,
      status,
      "ada",
      0
    )

  def spec = suite("Policy")(
    test("a draft naming a field its record lacks is refused with that error") {
      val refused = Draft.validate(draft("""{"op":"gt","path":"amont","value":1000}"""))
      assertTrue(refused.left.exists(_.getMessage.contains("no field 'amont'")))
    },
    test("a draft over the record's own fields is its rule") {
      assertTrue(
        Draft.validate(draft("""{"op":"gt","path":"amount","value":1000}""")) ==
          Right(Rule.Gt(FieldPath.unsafe("amount"), 1000))
      )
    },
    test("a draft reading a text field as a number is refused") {
      assertTrue(Draft.validate(draft("""{"op":"gt","path":"currency","value":1}""")).isLeft)
    },
    test("a rule over movements cannot read a proposed transfer's fields") {
      assertTrue(Draft.validate(draft("""{"op":"eq","path":"to","value":"x"}""", Subject.Movements)).isLeft)
    },
    test("a rule's latest live version is the one in force") {
      val live = LiveRules.of(Subject.Transfers, List(version("a", 1, Status.Live), version("a", 2, Status.Live)))
      assertTrue(live.rules.map(rule => rule.name -> rule.version) == List("a" -> 2))
    },
    test("a rule switched off has no live version, and keeps every version it had") {
      val history = List(version("a", 1, Status.Live), version("a", 2, Status.Off))
      assertTrue(LiveRules.of(Subject.Transfers, history).rules.isEmpty, history.size == 2)
    },
    test("a draft leaves the live version in force") {
      val live = LiveRules.of(Subject.Transfers, List(version("a", 1, Status.Live), version("a", 2, Status.Draft)))
      assertTrue(live.rules.map(_.version) == List(1))
    },
    test("a version waiting for approval, or refused it, leaves the version before it in force") {
      val history = List(
        version("a", 1, Status.Live),
        version("a", 2, Status.Live).copy(approval = Approval.Waiting("req-1", "h")),
        version("a", 3, Status.Off).copy(approval = Approval.Refused("req-2", "rejected"))
      )
      val approved = history.updated(1, history(1).copy(approval = Approval.Given("req-1", "h")))
      assertTrue(
        LiveRules.of(Subject.Transfers, history).rules.map(_.version) == List(1),
        LiveRules.of(Subject.Transfers, approved).rules.map(_.version) == List(2)
      )
    },
    test("the hash of what is asked is Approvals' own: before, after and facts, the facts in key order") {
      // The same vector bank-approvals' ProposalTest holds its Proposal.contentHash to.
      val expected = Proposal.contentHash(
        "amount is at least 1000",
        "amount is at least 500",
        Map("severity" -> "high", "change" -> "tighten")
      )
      assertTrue(
        expected == Proposal.contentHash(
          "amount is at least 1000",
          "amount is at least 500",
          Map("change" -> "tighten", "severity" -> "high")
        ),
        expected == "47d22c0de0e7bbc23492e9967a25cf4268cd1e6c0ef15f87639dc13a76c05408"
      )
    },
    test("live rules are in the admin's order") {
      val live = LiveRules.of(Subject.Transfers, List(version("b", 1, Status.Live, 1), version("a", 1, Status.Live, 2)))
      assertTrue(live.rules.map(_.name) == List("b", "a"))
    }
  )
