package bank.approvals.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PolicyTest {

    private val lowerOnly = Condition(mapOf("direction" to "lower"))
    private val limits = Policy("bank.account-limit", approvers = setOf("ops"), needed = 1, autoApprove = listOf(lowerOnly))

    @Test
    fun `a condition matches facts that hold every one of its keys, extra facts or not`() {
        limits.autoApproval(mapOf("direction" to "lower", "by" to "100")) shouldBe lowerOnly
        limits.autoApproval(mapOf("direction" to "raise")).shouldBeNull()
        limits.autoApproval(emptyMap()).shouldBeNull()
    }

    @Test
    fun `a condition with no facts would approve everything, and is refused`() {
        shouldThrow<IllegalArgumentException> { Condition(emptyMap()) }
    }

    @Test
    fun `a policy needs at least one approver from at least one group`() {
        shouldThrow<IllegalArgumentException> { Policy("k", approvers = setOf("ops"), needed = 0) }
        shouldThrow<IllegalArgumentException> { Policy("k", approvers = emptySet(), needed = 1) }
    }
}
