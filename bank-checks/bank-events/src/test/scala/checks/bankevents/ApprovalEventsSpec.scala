package checks.bankevents

import bank.events.v1.ApprovalEvent
import checks.policy.service.Decision
import zio.test.*

object ApprovalEventsSpec extends ZIOSpecDefault:
  private def event(kind: String = "checks.rule-version") =
    ApprovalEvent.newBuilder().setRequestId("req-1").setSequence(5).setKind(kind).setSubject("checks/rule/large")

  def spec = suite("Approval events")(
    test("approval given, automatically or by people, is a decision for the content it names") {
      val byPeople = event().setApprovalGiven(ApprovalEvent.ApprovalGiven.newBuilder().setContentHash("h1")).build()
      val auto     = event().setAutoApproved(ApprovalEvent.AutoApproved.newBuilder().setContentHash("h2")).build()
      assertTrue(
        ApprovalEvents.decision(byPeople).contains(Decision.Given("req-1", "h1")),
        ApprovalEvents.decision(auto).contains(Decision.Given("req-1", "h2"))
      )
    },
    test("a request rejected, withdrawn, superseded or expired ends without approval") {
      val rejected   = event().setRejected(ApprovalEvent.Rejected.newBuilder().setComment("too low")).build()
      val superseded = event().setSuperseded(ApprovalEvent.Superseded.newBuilder().setByRequestId("req-2")).build()
      val expired    = event().setExpired(ApprovalEvent.Expired.getDefaultInstance).build()
      assertTrue(
        ApprovalEvents.decision(rejected).contains(Decision.Ended("req-1", "rejected")),
        ApprovalEvents.decision(superseded).contains(Decision.Ended("req-1", "superseded")),
        ApprovalEvents.decision(expired).contains(Decision.Ended("req-1", "expired"))
      )
    },
    test("another kind's events, and the record's own, are not the check's to act on") {
      val theirs  = event("bank.account-limit").setApprovalGiven(ApprovalEvent.ApprovalGiven.newBuilder()).build()
      val comment = event().setCommented(ApprovalEvent.Commented.newBuilder().setText("why?")).build()
      assertTrue(ApprovalEvents.decision(theirs).isEmpty, ApprovalEvents.decision(comment).isEmpty)
    }
  )
