package bank.approvals.app

import bank.approvals.api.AppliedBody
import bank.approvals.api.ApproveBody
import bank.approvals.api.CommentBody
import bank.approvals.api.NewRequest
import bank.approvals.api.RequestView
import bank.approvals.api.Requester
import bank.approvals.api.approve
import bank.approvals.api.applied
import bank.approvals.api.ask
import bank.approvals.api.comment
import bank.approvals.api.health
import bank.approvals.api.read
import bank.approvals.domain.RequestId
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.signedInAs
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** lark-bank spec 0019's approvals-saga, against Postgres and real tokens. */
class SagaSpec {

    private fun change(subject: String, kind: String = "test.change", facts: Map<String, String> = emptyMap()) =
        NewRequest(kind, subject, "a rule, version 2", before = "amount is at least 1000", after = "amount is at least 500", facts = facts)

    private fun ApiClient.asked(subject: String, kind: String = "test.change", facts: Map<String, String> = emptyMap()): RequestView =
        outcome(ask, change(subject, kind, facts)).shouldBeOk()

    private fun ApiClient.approved(view: RequestView): Outcome<*, RequestView> =
        outcome(approve, In2(view.id, ApproveBody(view.hash)))

    @Test
    fun `a request approved on one node after its entity moved from another ends applied once, with its whole history`() {
        val cluster = TestCluster(2)
        cluster.node(0).use { staying: TestNode ->
            val second = Detached(cluster.node(1))
            val leaving = second.awaitUp()
            eventually { staying.cluster.view.members.count { it.status.name == "Up" } == 2 } shouldBe true

            // Enough requests that some live on the node that leaves; each asked, commented on and approved once
            // through that node, then approved again and applied through the one that stays.
            val rae = leaving.calling("rae", "engineers")
            val ada = leaving.calling("ada", "risk")
            val asked = (1..12).map { n -> rae.asked("test/subject-$n") }
            asked.forEach { view ->
                ada.outcome(comment, In2(view.id, CommentBody("why 500?"))).shouldBeOk()
                ada.approved(view).shouldBeOk()
            }

            second.stop()

            val bob = staying.calling("bob", "risk")
            val service = staying.calling("checks", "services")
            asked.forEach { view ->
                withClue("request ${view.id} approved again once its node has gone") {
                    eventually { bob.approved(view) is Outcome.Ok } shouldBe true
                }
                eventually { service.outcome(applied, In2(view.id, AppliedBody(view.hash))) is Outcome.Ok } shouldBe true
                // Said twice, applied once.
                service.outcome(applied, In2(view.id, AppliedBody(view.hash))).shouldBeOk()
            }

            val reader = staying.calling("rae", "engineers")
            asked.forEach { view ->
                val now = reader.outcome(read, view.id).shouldBeOk()
                withClue(now.timeline.joinToString { it.what }) {
                    now.state shouldBe "applied"
                    now.timeline.count { it.what == "applied" } shouldBe 1
                    now.timeline.map { it.what } shouldContainAll listOf("requested", "commented", "approved", "approval-given")
                    now.approvedBy.map { it.subject } shouldBe listOf("ada", "bob")
                }
            }
        }.shouldBeRight()
    }

    @Test
    fun `an owner that does not answer is asked again until it does, and not after`() {
        val cluster = TestCluster(1)
        cluster.node(0).use { node: TestNode ->
            val view = node.calling("rae", "engineers").asked("test/unanswered")
            node.calling("ada", "risk").approved(view).shouldBeOk()
            node.calling("bob", "risk").approved(view).shouldBeOk()

            val id = RequestId(view.id)
            // Asked every 300 ms while the owner stays silent.
            eventually { cluster.owners.times(id) >= 3 } shouldBe true
            node.calling("checks", "services").outcome(applied, In2(view.id, AppliedBody(view.hash))).shouldBeOk()

            val after = cluster.owners.times(id)
            Thread.sleep(1_000)
            cluster.owners.times(id) shouldBe after
            after shouldBeGreaterThanOrEqual 3
        }.shouldBeRight()
    }

    @Test
    fun `a newer request for the same subject supersedes the one still waiting`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val rae = node.calling("rae", "engineers")
            val first = rae.asked("test/twice")
            val second = rae.asked("test/twice")

            rae.outcome(read, first.id).shouldBeOk().state shouldBe "superseded"
            rae.outcome(read, second.id).shouldBeOk().state shouldBe "awaiting-approval"
            // The superseded one's votes are over.
            node.calling("ada", "risk").response(approve, In2(first.id, ApproveBody(first.hash))).status shouldBe 409
        }.shouldBeRight()
    }

    @Test
    fun `a request with no decision in its time expires on its own`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val rae = node.calling("rae", "engineers")
            val view = rae.asked("test/slow", kind = "test.short")

            eventually(15) { rae.outcome(read, view.id).shouldBeOk().state == "expired" } shouldBe true
        }.shouldBeRight()
    }

    @Test
    fun `facts meeting the policy's condition are approved with nobody looking, and still asked to apply`() {
        val cluster = TestCluster(1)
        cluster.node(0).use { node: TestNode ->
            val view = node.calling("rae", "engineers").asked("test/rename", kind = "test.auto", facts = mapOf("change" to "describe-only"))

            view.state shouldBe "approved"
            view.approvedAutomatically shouldBe mapOf("change" to "describe-only")
            eventually { cluster.owners.times(RequestId(view.id)) >= 1 } shouldBe true
        }.shouldBeRight()
    }

    @Test
    fun `a vote the rules turn away is refused, and on the record`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val rae = node.calling("rae", "engineers", "risk")
            val view = rae.asked("test/own")

            rae.response(approve, In2(view.id, ApproveBody(view.hash))).status shouldBe 409
            rae.outcome(read, view.id).shouldBeOk().timeline.last().let {
                it.what shouldBe "vote-refused"
                it.text shouldBe "OwnRequest"
            }
        }.shouldBeRight()
    }

    @Test
    fun `a service asks for the person who made the change, who may then not approve it, and nobody else names a person`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val asked = change("test/on-behalf").copy(requestedBy = Requester("ada", "Ada"))
            val view = node.calling("checks", "services").outcome(ask, asked).shouldBeOk()

            view.requester.subject shouldBe "ada"
            node.calling("ada", "risk").response(approve, In2(view.id, ApproveBody(view.hash))).status shouldBe 409
            node.calling("ada", "risk").outcome(read, view.id).shouldBeOk().timeline.last().text shouldBe "OwnRequest"
            node.calling("rae", "engineers").response(ask, asked.copy(subject = "test/on-behalf-2")).status shouldBe 403
        }.shouldBeRight()
    }

    @Test
    fun `a service's client-credentials token, as Pocket ID issues one, speaks for that service, and no other client's does`() {
        TestCluster(1).node(0).use { node: TestNode ->
            // As Pocket ID's: the client its audience, `client-` and its id the subject, and no groups.
            val service = TestIdentity.issuer.token("client-checks-service", emptyList(), "checks-service")
            val rogue = TestIdentity.issuer.token("client-rogue", emptyList(), "rogue")
            val asked = change("test/by-client").copy(requestedBy = Requester("ada", "Ada"))

            val view = node.client().signedInAs(service).outcome(ask, asked).shouldBeOk()
            view.requester.subject shouldBe "ada"
            node.client().signedInAs(rogue).response(ask, asked.copy(subject = "test/by-rogue")).status shouldBe 401
        }.shouldBeRight()
    }

    @Test
    fun `nobody reads a request they are not on, and nobody without a token reads any`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val view = node.calling("rae", "engineers").asked("test/private")

            node.calling("eve", "marketing").response(read, view.id).status shouldBe 403
            node.client().response(read, view.id).status shouldBe 401
            node.client().response(health, Unit).status shouldBe 200
            node.calling("aud", "auditor").outcome(read, view.id).shouldBeOk()
        }.shouldBeRight()
    }
}
