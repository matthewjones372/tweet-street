package bank.approvals.protocol

import bank.approvals.domain.Chain
import bank.approvals.domain.Condition
import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import bank.approvals.domain.Policies
import bank.approvals.domain.Policy
import bank.approvals.domain.Proposal
import bank.approvals.domain.Refusal
import bank.approvals.domain.RefusedBecause
import bank.approvals.domain.Request
import bank.approvals.domain.RequestCommand
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.approvals.domain.decide
import bank.approvals.domain.evolve
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Every event reads back as it was written, byte for byte the same each time, and every refusal crosses by name. */
class RoundTripTest {

    private val t0 = Instant.parse("2026-10-01T09:00:00Z")
    private val rae = Person("rae", "Rae", setOf("engineers", "checks"))
    private val ada = Person("ada", "Ada", setOf("risk"))
    private val checks = Person("checks", "the check", setOf("services"))
    private val desk = Origin("session-1", "192.0.2.20", "Firefox")
    private val policy = Policy(
        "checks.rule-version", setOf("risk", "ops"), 2, Duration.ofDays(7),
        listOf(Condition(mapOf("change" to "switch-off", "severity" to "low"))),
    )
    private val proposal = Proposal(
        "checks.rule-version", "checks/rule/large-transfer", "v4", "amount is at least 1000", "amount is at least 500",
        mapOf("severity" to "high", "change" to "tighten"), impact = """{"declined":7}""", link = null,
    )

    private val everyEvent: List<RequestEvent> = run {
        val asked = decide(Request.Unasked, RequestCommand.Ask(RequestId("req-1"), proposal, rae, Policies("v1", mapOf(proposal.kind to policy)), t0))
            .getOrNull()!!
        val requested = asked.first() as RequestEvent.Requested
        asked + listOf(
            RequestEvent.AutoApproved(Condition(mapOf("change" to "describe-only")), t0),
            RequestEvent.Approved(ada, proposal.contentHash, "fine", t0, desk),
            RequestEvent.Approved(ada, proposal.contentHash, null, t0),
            RequestEvent.ApprovalGiven(listOf("ada", "bob"), t0),
            RequestEvent.Rejected(ada, "too low", t0),
            RequestEvent.Commented(rae, "why?", t0),
            RequestEvent.VoteRefused(rae, RefusedBecause.OwnRequest, t0, desk),
            RequestEvent.Withdrawn(rae, t0),
            RequestEvent.Superseded(RequestId("req-2"), t0),
            RequestEvent.Expired(t0),
            RequestEvent.Applied(t0),
            RequestEvent.Applied(t0, checks, proposal.contentHash, desk),
            RequestEvent.ApplyFailed("down", t0),
            RequestEvent.ApplyFailed("down", t0, checks, desk),
        ).also { requested.asked.hash shouldBe proposal.contentHash }
    }

    @Test
    fun `every event reads back as the one written`() {
        chained(null, everyEvent).forEach { event -> RequestEvents.decode(RequestEvents.encode(event)) shouldBe event }
    }

    @Test
    fun `each event's link follows from the one before and the bytes stored, and the chain reads back as written`() {
        val links = chained(null, everyEvent).map { RequestEvents.encode(it) }.map { StoredLinks.decode(it) }

        Chain.firstBroken(links.map { Chain.Stored(it.event.toHexString(), it.link) }) shouldBe null
        links.first().link shouldBe Chain.link(null, everyEvent.first().stored().toHexString())
        // Continued from a head, a chain links to it.
        chained(links.last().link, everyEvent.take(1)).single().link shouldBe
            Chain.link(links.last().link, everyEvent.first().stored().toHexString())
    }

    @Test
    fun `an event's stored bytes are the same each time, whatever order its facts arrived in`() {
        val requested = everyEvent.first() as RequestEvent.Requested
        val reordered = requested.copy(
            asked = requested.asked.copy(proposal = proposal.copy(facts = linkedMapOf("change" to "tighten", "severity" to "high"))),
        )
        requested.stored().contentEquals(reordered.stored()) shouldBe true
    }

    @Test
    fun `a history replays the same after crossing`() {
        val history = everyEvent.take(2)
        val crossed = chained(null, history).map { RequestEvents.decode(RequestEvents.encode(it)).event }
        crossed.fold(Request.Unasked as Request, ::evolve) shouldBe history.fold(Request.Unasked as Request, ::evolve)
    }

    @Test
    fun `every refusal has a name the wire knows`() {
        val named = refusals.values.map { it::class }.toSet()
        Refusal::class.sealedSubclasses.toSet() shouldBe named
        refusals.forEach { (name, refusal) -> refusal::class.simpleName shouldBe name }
    }
}
