package bank.approvals.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** What is approved cannot change after, and tampering with the record shows (lark-bank spec 0019's audit). */
class IntegrityTest {

    private val proposal = Proposal(
        kind = "checks.rule-version", subject = "checks/rule/large-transfer", title = "v4",
        before = "amount is at least 1000", after = "amount is at least 500", facts = mapOf("change" to "tighten"),
    )

    @Test
    fun `the content hash is one an owning service can make itself, as bank-checks' PolicySpec does`() {
        val proposal = Proposal(
            "checks.rule-version", "checks/rule/large", "large", "amount is at least 1000", "amount is at least 500",
            facts = mapOf("severity" to "high", "change" to "tighten"),
        )
        proposal.contentHash shouldBe "47d22c0de0e7bbc23492e9967a25cf4268cd1e6c0ef15f87639dc13a76c05408"
    }

    @Test
    fun `the content hash covers before, after and facts, and nothing shown only beside them`() {
        proposal.copy(after = "amount is at least 50").contentHash shouldNotBe proposal.contentHash
        proposal.copy(before = "").contentHash shouldNotBe proposal.contentHash
        proposal.copy(facts = mapOf("change" to "loosen")).contentHash shouldNotBe proposal.contentHash

        proposal.copy(title = "a better title", impact = "{}", link = "https://x").contentHash shouldBe proposal.contentHash
    }

    @Test
    fun `facts hash the same in any order`() {
        val one = proposal.copy(facts = linkedMapOf("a" to "1", "b" to "2"))
        val other = proposal.copy(facts = linkedMapOf("b" to "2", "a" to "1"))

        one.contentHash shouldBe other.contentHash
    }

    @Test
    fun `the text cannot be moved from before to after without the hash changing`() {
        proposal.copy(before = "x", after = "yz").contentHash shouldNotBe proposal.copy(before = "xy", after = "z").contentHash
    }

    private val events = listOf("Requested req-1", "Approved ada", "Commented bob: why?", "Approved bob", "Applied")
    private val stored = events.zip(Chain.links(events)) { event, link -> Chain.Stored(event, link) }

    @Test
    fun `an untouched chain verifies`() {
        Chain.firstBroken(stored).shouldBeNull()
    }

    @Test
    fun `an edited event is named, and it is the first that fails`() {
        val edited = stored.toMutableList().apply { set(2, get(2).copy(event = "Commented bob: fine")) }

        Chain.firstBroken(edited) shouldBe 2
    }

    @Test
    fun `a dropped or reordered event breaks the chain where it happened`() {
        Chain.firstBroken(stored.filterIndexed { index, _ -> index != 1 }) shouldBe 1
        Chain.firstBroken(listOf(stored[0], stored[2], stored[1], stored[3], stored[4])) shouldBe 1
    }

    @Test
    fun `an event rewritten with a freshly computed link still breaks the next one`() {
        val forged = stored.toMutableList().apply {
            set(1, Chain.Stored("Approved eve", Chain.link(stored[0].link, "Approved eve")))
        }

        Chain.firstBroken(forged) shouldBe 2
    }
}
