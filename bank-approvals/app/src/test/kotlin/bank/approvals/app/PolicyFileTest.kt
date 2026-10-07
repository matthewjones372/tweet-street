package bank.approvals.app

import bank.approvals.domain.Condition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration

class PolicyFileTest {

    @Test
    fun `the policies shipped with the service read, as spec 0019 wrote them`() {
        val shipped = PolicyFile(Path.of("../policies/policies.yaml")).current()

        shipped.version shouldStartWith "policies@"
        val rules = shipped.forKind("checks.rule-version")
        rules.approvers shouldBe setOf("risk")
        rules.needed shouldBe 2
        rules.expiresAfter shouldBe Duration.ofDays(7)
        rules.autoApproval(mapOf("change" to "switch-off", "severity" to "low")) shouldBe
            Condition(mapOf("change" to "switch-off", "severity" to "low"))
        shipped.forKind("checks.rule-off").approvers shouldBe setOf("risk", "ops")
        val grants = shipped.forKind("bank.access-grant")
        grants.approvers shouldBe setOf("support", "ops")
        grants.needed shouldBe 1
        grants.expiresAfter shouldBe Duration.ofHours(1)
    }

    @Test
    fun `a changed file is read again, with a new version`() {
        val path = Files.createTempFile("policies", ".yaml")
        Files.writeString(path, "- kind: k\n  approvers: [ ops ]\n  needed: 1\n")
        val file = PolicyFile(path)
        val before = file.current()

        Files.writeString(path, "- kind: k\n  approvers: [ ops ]\n  needed: 2\n")
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        val after = file.current()

        after.forKind("k").needed shouldBe 2
        after.version shouldNotBe before.version
    }

    @Test
    fun `a kind listed twice is refused, since which one wins would be an accident`() {
        shouldThrow<IllegalArgumentException> {
            parsePolicies("- kind: k\n  approvers: [ a ]\n  needed: 1\n- kind: k\n  approvers: [ b ]\n  needed: 1\n", "v")
        }.message shouldContain "[k]"
    }
}
