package bank.approvals.app

import bank.approvals.api.AppliedBody
import bank.approvals.api.ApproveBody
import bank.approvals.api.CommentBody
import bank.approvals.api.NewRequest
import bank.approvals.api.applied
import bank.approvals.api.approve
import bank.approvals.api.ask
import bank.approvals.api.comment
import bank.events.v1.ApprovalEvent
import bank.events.v1.ApprovalEvent.EventCase
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** lark-bank spec 0019's approvals-published: every request's events on `bank.approval-events`, as a consumer reads them. */
class PublishedSpec {
    private val run = UUID.randomUUID().toString().take(8)

    private fun change(subject: String) =
        NewRequest("test.change", subject, "a rule, version 2", before = "amount is at least 1000", after = "amount is at least 500")

    @Test
    fun `a consumer reads a request's events in order, each with its kind and subject, as Apicurio's deserializer reads them`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val subject = "test/published-$run"
            val view = node.calling("rae", "engineers").outcome(ask, change(subject)).shouldBeOk()
            node.calling("ada", "risk").outcome(comment, In2(view.id, CommentBody("why 500?"))).shouldBeOk()
            node.calling("ada", "risk").outcome(approve, In2(view.id, ApproveBody(view.hash, "fine"))).shouldBeOk()
            node.calling("bob", "risk").outcome(approve, In2(view.id, ApproveBody(view.hash))).shouldBeOk()
            node.calling("checks", "services").outcome(applied, In2(view.id, AppliedBody(view.hash))).shouldBeOk()

            val read = TestKafka.read(setOf(view.id)) { seen -> seen.any { it.eventCase == EventCase.APPLIED } }
            // At least once: what a consumer keeps is the first of each sequence.
            val events = read.distinctBy(ApprovalEvent::getSequence)
            withClue(read.joinToString { "${it.sequence}:${it.eventCase}" }) {
                events.map { it.eventCase } shouldContainExactly listOf(
                    EventCase.REQUESTED, EventCase.AWAITING_APPROVAL, EventCase.COMMENTED,
                    EventCase.APPROVED, EventCase.APPROVED, EventCase.APPROVAL_GIVEN, EventCase.APPLIED,
                )
                events.map { it.sequence } shouldBe (1L..7L).toList()
            }
            events.forEach { event ->
                event.requestId shouldBe view.id
                event.kind shouldBe "test.change"
                event.subject shouldBe subject
            }
            events[0].requested.let { requested ->
                requested.contentHash shouldBe view.hash
                requested.requester.subject shouldBe "rae"
                requested.approversList shouldBe listOf("risk")
                requested.needed shouldBe 2
            }
            events[3].approved.let { approved ->
                approved.by.subject shouldBe "ada"
                approved.comment shouldBe "fine"
                approved.contentHash shouldBe view.hash
            }
            events[5].approvalGiven.approversList shouldBe listOf("ada", "bob")
        }.shouldBeRight()
    }

    @Test
    fun `an owner that does not answer hears the approval again on Kafka, under the same sequence, until it does`() {
        TestCluster(1, onKafka = true).node(0).use { node: TestNode ->
            val view = node.calling("rae", "engineers").outcome(ask, change("test/silent-$run")).shouldBeOk()
            node.calling("ada", "risk").outcome(approve, In2(view.id, ApproveBody(view.hash))).shouldBeOk()
            node.calling("bob", "risk").outcome(approve, In2(view.id, ApproveBody(view.hash))).shouldBeOk()

            val given = TestKafka.read(setOf(view.id)) { seen -> seen.count { it.eventCase == EventCase.APPROVAL_GIVEN } >= 3 }
                .filter { it.eventCase == EventCase.APPROVAL_GIVEN }
            given.size shouldBeGreaterThanOrEqual 3
            given.map { it.sequence }.toSet() shouldBe setOf(5L)

            node.calling("checks", "services").outcome(applied, In2(view.id, AppliedBody(view.hash))).shouldBeOk()
            val after = TestKafka.read(setOf(view.id)) { seen -> seen.any { it.eventCase == EventCase.APPLIED } }
            // Once applied, nobody is asked again.
            Thread.sleep(1_000)
            val settled = TestKafka.read(setOf(view.id), seconds = 3) { seen -> seen.size > after.size }
            settled.size shouldBe after.size
        }.shouldBeRight()
    }
}
