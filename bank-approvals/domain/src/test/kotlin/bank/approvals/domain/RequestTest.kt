package bank.approvals.domain

import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Every path of the saga in lark-bank spec 0019, and every vote the rules turn away. */
class RequestTest {

    private val t0 = Instant.parse("2026-10-01T09:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)

    private val ada = Person("ada", "Ada", setOf("risk"))
    private val bob = Person("bob", "Bob", setOf("risk"))
    private val cy = Person("cy", "Cy", setOf("risk"))
    private val olga = Person("olga", "Olga", setOf("ops"))
    private val rae = Person("rae", "Rae", setOf("engineers"))
    private val checks = Person("checks", "the check", setOf("services"))

    private val policies = Policies(
        version = "policies@abc123",
        byKind = mapOf(
            "checks.rule-version" to Policy(
                "checks.rule-version", approvers = setOf("risk"), needed = 2, expiresAfter = Duration.ofDays(7),
                autoApprove = listOf(Condition(mapOf("change" to "switch-off", "severity" to "low")), Condition(mapOf("change" to "describe-only"))),
            ),
        ),
    )

    private val tighten = Proposal(
        kind = "checks.rule-version", subject = "checks/rule/large-transfer", title = "large-transfer, version 4",
        before = "amount is at least 1000", after = "amount is at least 500",
        facts = mapOf("change" to "tighten", "severity" to "high"),
    )

    /** The history so far, and the request it makes; each step decided and applied, as the entity will. */
    private class Saga(var request: Request = Request.Unasked, val history: MutableList<RequestEvent> = mutableListOf()) {
        fun run(command: RequestCommand): Either<Refusal, List<RequestEvent>> =
            decide(request, command).onRight { events ->
                history += events
                request = events.fold(request, ::evolve)
            }
    }

    private fun asked(proposal: Proposal = tighten, by: Person = rae): Saga =
        Saga().apply { run(RequestCommand.Ask(RequestId("req-1"), proposal, by, policies, t0)).shouldBeRight() }

    private fun Saga.approve(by: Person, minutes: Long, hash: String = tighten.contentHash) =
        run(RequestCommand.Approve(by, hash, at = at(minutes))).shouldBeRight()

    // ---- the ways a request goes ----

    @Test
    fun `asked, then two approvers, is given approval, and applied ends it`() {
        val saga = asked()
        saga.history.map { it::class.simpleName } shouldBe listOf("Requested", "AwaitingApproval")

        saga.approve(ada, 1)
        saga.request.shouldBeInstanceOf<Request.Waiting>().approvedBy shouldBe listOf(ada)
        saga.approve(bob, 2).map { it::class.simpleName } shouldBe listOf("Approved", "ApprovalGiven")
        saga.request.shouldBeInstanceOf<Request.Agreed>()

        saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(3))).shouldBeRight()
        saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Applied
    }

    @Test
    fun `a failed apply is recorded, the request stays agreed, and the next attempt applies it`() {
        val saga = asked().apply { approve(ada, 1); approve(bob, 2) }

        saga.run(RequestCommand.MarkApplyFailed("the check's database was down", at(3))).shouldBeRight()
        saga.request.shouldBeInstanceOf<Request.Agreed>()
        saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(4))).shouldBeRight()
        saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Applied
    }

    @Test
    fun `applied said twice is applied once`() {
        val saga = asked().apply { approve(ada, 1); approve(bob, 2) }
        saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(3))).shouldBeRight()

        saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(4))).shouldBeRight().shouldBeEmpty()
        saga.history.count { it is RequestEvent.Applied } shouldBe 1
    }

    @Test
    fun `applied of content other than what was approved is refused, before and after the real one`() {
        val saga = asked().apply { approve(ada, 1); approve(bob, 2) }
        val swapped = tighten.copy(after = "amount is at least 5").contentHash

        saga.run(RequestCommand.MarkApplied(swapped, at(3))).shouldBeLeft(Refusal.NotWhatWasApproved)
        saga.request.shouldBeInstanceOf<Request.Agreed>()
        saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(4), by = checks)).shouldBeRight()
        saga.run(RequestCommand.MarkApplied(swapped, at(5))).shouldBeLeft(Refusal.NotWhatWasApproved)
        saga.history.last().shouldBeInstanceOf<RequestEvent.Applied>().let {
            it.by shouldBe checks
            it.hash shouldBe tighten.contentHash
        }
    }

    @Test
    fun `every act by a person keeps where it came from, a refused vote's included`() {
        val desk = Origin("session-1", "192.0.2.20", "Firefox")
        val saga = Saga().apply { run(RequestCommand.Ask(RequestId("req-1"), tighten, rae, policies, t0, desk)).shouldBeRight() }

        saga.run(RequestCommand.Approve(rae, tighten.contentHash, at = at(1), origin = desk)).shouldBeRight()
        saga.run(RequestCommand.Comment(ada, "why?", at(2), desk)).shouldBeRight()
        saga.run(RequestCommand.Approve(ada, tighten.contentHash, at = at(3), origin = desk)).shouldBeRight()

        saga.history[0].shouldBeInstanceOf<RequestEvent.Requested>().origin shouldBe desk
        saga.history[2].shouldBeInstanceOf<RequestEvent.VoteRefused>().origin shouldBe desk
        saga.history[3].shouldBeInstanceOf<RequestEvent.Commented>().origin shouldBe desk
        saga.history[4].shouldBeInstanceOf<RequestEvent.Approved>().origin shouldBe desk
    }

    @Test
    fun `facts meeting a policy's condition are approved automatically, and still recorded as a request`() {
        val switchOff = tighten.copy(facts = mapOf("change" to "switch-off", "severity" to "low", "rule" to "small-atm"))
        val saga = asked(switchOff)

        val auto = saga.history[1].shouldBeInstanceOf<RequestEvent.AutoApproved>()
        auto.condition shouldBe Condition(mapOf("change" to "switch-off", "severity" to "low"))
        saga.history[0].shouldBeInstanceOf<RequestEvent.Requested>().asked.policyVersion shouldBe "policies@abc123"
        saga.request.shouldBeInstanceOf<Request.Agreed>().automatically shouldBe auto.condition
    }

    @Test
    fun `facts meeting only part of a condition wait for people`() {
        val switchOffHigh = tighten.copy(facts = mapOf("change" to "switch-off", "severity" to "high"))

        asked(switchOffHigh).request.shouldBeInstanceOf<Request.Waiting>()
    }

    @Test
    fun `a kind with no policy needs two admins, and nothing is approved automatically`() {
        val unknown = tighten.copy(kind = "bank.something-new", facts = mapOf("change" to "describe-only"))
        val saga = asked(unknown)

        saga.history[1] shouldBe RequestEvent.AwaitingApproval(2, setOf("admins"), t0)
    }

    @Test
    fun `a rejection with its reason ends the request`() {
        val saga = asked().apply { approve(ada, 1) }

        saga.run(RequestCommand.Reject(bob, "the bound is too low", at(2))).shouldBeRight()
        saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Rejected(bob, "the bound is too low")
    }

    @Test
    fun `a rejection needs a reason`() {
        asked().run(RequestCommand.Reject(bob, "  ", at(1))).shouldBeLeft(Refusal.CommentRequired)
    }

    @Test
    fun `one who approved may still reject, and the approval stays in the history`() {
        val saga = asked().apply { approve(ada, 1) }

        saga.run(RequestCommand.Reject(ada, "I misread the impact", at(2))).shouldBeRight()
        saga.history.filterIsInstance<RequestEvent.Approved>().single().by shouldBe ada
        saga.request.shouldBeInstanceOf<Request.Ended>().approvedBy shouldBe listOf(ada)
    }

    @Test
    fun `the requester may withdraw it while it waits, and nobody else may`() {
        asked().run(RequestCommand.Withdraw(ada, at(1))).shouldBeLeft(Refusal.OnlyTheRequesterWithdraws)

        val saga = asked()
        saga.run(RequestCommand.Withdraw(rae, at(1))).shouldBeRight()
        saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Withdrawn(rae)
    }

    @Test
    fun `nobody may withdraw it once it is approved`() {
        val saga = asked().apply { approve(ada, 1); approve(bob, 2) }

        saga.run(RequestCommand.Withdraw(rae, at(3))).shouldBeLeft(Refusal.NotWaiting)
    }

    @Test
    fun `a newer request for the same subject supersedes one still waiting`() {
        val saga = asked().apply { approve(ada, 1) }

        saga.run(RequestCommand.Supersede(RequestId("req-2"), at(2))).shouldBeRight()
        saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Superseded(RequestId("req-2"))
    }

    @Test
    fun `a request with no decision in its time expires, and not a moment before`() {
        val saga = asked()

        saga.run(RequestCommand.Expire(t0.plus(Duration.ofDays(7)).minusSeconds(1))).shouldBeLeft(Refusal.NotYetDue)
        saga.run(RequestCommand.Expire(t0.plus(Duration.ofDays(7)))).shouldBeRight()
        saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Expired
    }

    @Test
    fun `an expiry timer that fires after the end finds nothing to do`() {
        val saga = asked().apply { approve(ada, 1); approve(bob, 2) }

        saga.run(RequestCommand.Expire(t0.plus(Duration.ofDays(8)))).shouldBeRight().shouldBeEmpty()
    }

    // ---- votes the rules turn away, each recorded ----

    private fun Saga.refusedAs(expected: RefusedBecause) {
        val refused = history.last().shouldBeInstanceOf<RequestEvent.VoteRefused>()
        refused.because shouldBe expected
    }

    @Test
    fun `a requester's own approval is refused, and the refusal is on the record`() {
        val requesterInRisk = Person("rae", "Rae", setOf("risk"))
        val saga = asked(by = requesterInRisk)

        saga.approve(requesterInRisk, 1)
        saga.refusedAs(RefusedBecause.OwnRequest)
        saga.request.shouldBeInstanceOf<Request.Waiting>().approvedBy.shouldBeEmpty()
    }

    @Test
    fun `a second approval from one person is refused, and counts once`() {
        val saga = asked().apply { approve(ada, 1); approve(ada, 2) }

        saga.refusedAs(RefusedBecause.AlreadyApproved)
        saga.request.shouldBeInstanceOf<Request.Waiting>().approvedBy shouldBe listOf(ada)
    }

    @Test
    fun `an approval from outside the policy's groups is refused`() {
        val saga = asked().apply { approve(olga, 1) }

        saga.refusedAs(RefusedBecause.NotAnApprover)
    }

    @Test
    fun `a vote after the end is refused`() {
        val saga = asked().apply { approve(ada, 1); approve(bob, 2) }

        saga.approve(cy, 3)
        saga.refusedAs(RefusedBecause.Ended)
        saga.run(RequestCommand.Reject(cy, "too late to say", at(4))).shouldBeRight()
        saga.refusedAs(RefusedBecause.Ended)
    }

    @Test
    fun `a vote cast on content other than what was asked is refused`() {
        val swapped = tighten.copy(after = "amount is at least 50")
        val saga = asked().apply { approve(ada, 1, hash = swapped.contentHash) }

        saga.refusedAs(RefusedBecause.NotWhatWasAsked)
    }

    // ---- the thread ----

    @Test
    fun `the requester and approvers comment, and a stranger may not`() {
        val saga = asked()

        saga.run(RequestCommand.Comment(ada, "why 500 and not 750?", at(1))).shouldBeRight()
        saga.run(RequestCommand.Comment(rae, "750 let three through last week", at(2))).shouldBeRight()
        saga.run(RequestCommand.Comment(olga, "looks fine to me", at(3))).shouldBeLeft(Refusal.NotOnTheRequest)

        saga.history.filterIsInstance<RequestEvent.Commented>().map { it.by } shouldBe listOf(ada, rae)
        saga.request.shouldBeInstanceOf<Request.Waiting>()
    }

    // ---- asking ----

    @Test
    fun `asking again with the same request is a retry, and with a different one is refused`() {
        val saga = asked()

        saga.run(RequestCommand.Ask(RequestId("req-1"), tighten, rae, policies, at(1))).shouldBeRight().shouldBeEmpty()
        saga.run(RequestCommand.Ask(RequestId("req-1"), tighten.copy(after = "never"), rae, policies, at(1)))
            .shouldBeLeft(Refusal.AskedDifferently)
    }

    @Test
    fun `the history replays to the same request`() {
        val saga = asked().apply {
            run(RequestCommand.Comment(ada, "a question", at(1)))
            approve(ada, 2); approve(ada, 3); approve(bob, 4)
            run(RequestCommand.MarkApplyFailed("down", at(5))); run(RequestCommand.MarkApplied(tighten.contentHash, at(6)))
        }

        replay(saga.history) shouldBe saga.request
    }
}
