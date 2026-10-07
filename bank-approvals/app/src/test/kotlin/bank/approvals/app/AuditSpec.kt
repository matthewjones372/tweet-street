package bank.approvals.app

import bank.approvals.api.AppliedBody
import bank.approvals.api.ApplyFailedBody
import bank.approvals.api.ApproveBody
import bank.approvals.api.CommentBody
import bank.approvals.api.NewRequest
import bank.approvals.api.RequestView
import bank.approvals.api.applied
import bank.approvals.api.applyFailed
import bank.approvals.api.approve
import bank.approvals.api.ask
import bank.approvals.api.auditEntries
import bank.approvals.api.auditExport
import bank.approvals.api.comment
import bank.approvals.api.read
import bank.approvals.api.verify
import bank.approvals.domain.Proposal
import bank.approvals.domain.RequestEvent
import bank.approvals.protocol.ChainedEvent
import bank.approvals.protocol.RequestEvents
import bank.approvals.protocol.chained
import bank.events.v1.ApprovalEvent.EventCase
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In5
import io.github.matthewjones372.pelican.In6
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** lark-bank spec 0019's approvals-audit: who and from where, the content hash on acting, the chains and their digests. */
class AuditSpec {
    private val run = UUID.randomUUID().toString().take(8)

    private val before = "amount is at least 1000"
    private val after = "amount is at least 500"

    private fun change(subject: String) = NewRequest("test.change", subject, "a rule, version 2", before = before, after = after)

    private fun ApiClient.asked(subject: String): RequestView = outcome(ask, change(subject)).shouldBeOk()

    private fun ApiClient.approved(view: RequestView) = outcome(approve, In2(view.id, ApproveBody(view.hash))).shouldBeOk()

    /** Request [id]'s stored events, each as the journal row holds it, in order. */
    private fun rows(database: String, id: String): List<ByteArray> = DriverManager.getConnection(database).use { connection ->
        connection.prepareStatement("select bytes from lark_journal where kind = 'request' and id = ? order by seq_nr").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getBytes(1) else null }.toList() }
        }
    }

    /** Writes [bytes] over request [id]'s event [sequence], as someone with the database's password could. */
    private fun overwrite(database: String, id: String, sequence: Long, bytes: ByteArray) = DriverManager.getConnection(database).use { connection ->
        connection.prepareStatement("update lark_journal set bytes = ? where kind = 'request' and id = ? and seq_nr = ?").use {
            it.setBytes(1, bytes)
            it.setString(2, id)
            it.setLong(3, sequence)
            it.executeUpdate() shouldBe 1
        }
    }

    private fun ByteArray.replacing(from: String, to: String): ByteArray {
        require(from.length == to.length) { "the same length, so the event still reads" }
        val text = String(this, Charsets.ISO_8859_1)
        text shouldContain from
        return text.replace(from, to).toByteArray(Charsets.ISO_8859_1)
    }

    private val today get() = LocalDate.now(ZoneOffset.UTC)

    @Test
    fun `one stored event's bytes edited, verify names that event, and only an approver or auditor may ask`() {
        val cluster = TestCluster(1)
        cluster.node(0).use { node: TestNode ->
            val view = node.calling("rae", "engineers").asked("test/edited-$run")
            node.calling("ada", "risk").approved(view)
            node.calling("bob", "risk").approved(view)
            val auditor = node.calling("aud", "auditor")
            auditor.outcome(verify, Unit).shouldBeOk().broken.shouldBeEmpty()

            // The first event, Requested: the rule as asked, changed to one nobody saw.
            overwrite(cluster.database, view.id, 1, rows(cluster.database, view.id)[0].replacing(after, "amount is at least 900"))

            val found = auditor.outcome(verify, Unit).shouldBeOk()
            withClue(found.broken.toString()) {
                found.broken.map { it.request to it.sequence } shouldBe listOf(view.id to 1L)
            }
            found.events shouldBe 5
            node.calling("eve", "marketing").response(verify, Unit).status shouldBe 403
        }.shouldBeRight()
    }

    @Test
    fun `a chain rewritten whole, every link recomputed, still walks, and the day's digest names it`() {
        val cluster = TestCluster(1)
        cluster.node(0).use { node: TestNode ->
            val view = node.calling("rae", "engineers").asked("test/rewritten-$run")
            node.calling("ada", "risk").approved(view)
            node.calling("bob", "risk").approved(view)
            val digest = node.audit.digest(today)
            digest.heads.single { it.request == view.id }.sequence shouldBe 5
            node.audit.digest(today) shouldBe digest

            // Every event read, the asked rule changed, and the chain made again from the first: each link follows.
            val events = rows(cluster.database, view.id).map { RequestEvents.decode(it).event }
            val requested = events.first() as RequestEvent.Requested
            val forged = listOf(
                requested.copy(asked = requested.asked.copy(proposal = requested.asked.proposal.copy(after = "amount is at least 5"))),
            ) + events.drop(1)
            chained(null, forged).map(RequestEvents::encode).forEachIndexed { at, bytes -> overwrite(cluster.database, view.id, at + 1L, bytes) }

            val found = node.calling("aud", "auditor").outcome(verify, Unit).shouldBeOk()
            withClue(found.broken.toString()) {
                found.broken.single().let {
                    it.request shouldBe view.id
                    it.sequence shouldBe 5
                    it.why shouldBe "does not match the digest of $today"
                }
            }
        }.shouldBeRight()
    }

    @Test
    fun `a change edited after two approvals is refused by its owner, and approvals refuses to record it applied`() {
        TestCluster(1, onKafka = true).node(0).use { node: TestNode ->
            // The owning service's change as it asked, and as it stands after someone edits it once approved.
            val asked = Proposal("test.change", "test/swapped-$run", "a rule, version 2", before, after)
            val edited = asked.copy(after = "amount is at least 5")
            val view = node.calling("checks", "services").asked(asked.subject)
            view.hash shouldBe asked.contentHash
            node.calling("ada", "risk").approved(view)
            node.calling("bob", "risk").approved(view)

            // The owner hears the approval, and checks the change in hand against the content it names.
            val given = TestKafka.read(setOf(view.id)) { seen -> seen.any { it.eventCase == EventCase.APPROVAL_GIVEN } }
                .first { it.eventCase == EventCase.APPROVAL_GIVEN }
            given.approvalGiven.contentHash shouldBe asked.contentHash
            val inHand = edited.contentHash
            inHand shouldNotBe given.approvalGiven.contentHash
            val owner = node.calling("checks", "services")
            owner.outcome(applyFailed, In2(view.id, ApplyFailedBody("the change was edited after it was approved"))).shouldBeOk()

            // Said applied of the edited change anyway, the record refuses it, and the request is still only approved.
            owner.response(applied, In2(view.id, AppliedBody(inHand))).status shouldBe 409
            owner.outcome(read, view.id).shouldBeOk().state shouldBe "approved"
            owner.outcome(applied, In2(view.id, AppliedBody(asked.contentHash))).shouldBeOk().state shouldBe "applied"
        }.shouldBeRight()
    }

    @Test
    fun `a person's history lists every vote, refused ones included, each with where it came from, and exports are recorded`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val ada = node.calling("ada", "risk").from("192.0.2.20", "Firefox/131")
            val theirs = node.calling("rae", "engineers").asked("test/theirs-$run")
            val hers = ada.asked("test/hers-$run")

            ada.outcome(comment, In2(theirs.id, CommentBody("why 500?"))).shouldBeOk()
            ada.approved(theirs)
            ada.response(approve, In2(theirs.id, ApproveBody(theirs.hash))).status shouldBe 409
            ada.response(approve, In2(hers.id, ApproveBody(hers.hash))).status shouldBe 409
            node.calling("bob", "risk").approved(theirs)

            val auditor = node.calling("aud", "auditor")
            val history = auditor.outcome(auditEntries, In5("ada", null, null, null, null)).shouldBeOk()
            withClue(history.joinToString { "${it.request.take(8)} ${it.what} ${it.text}" }) {
                history.map { it.what to it.text } shouldContainExactly listOf(
                    "requested" to "a rule, version 2",
                    "commented" to "why 500?",
                    "approved" to null,
                    "vote-refused" to "AlreadyApproved",
                    "vote-refused" to "OwnRequest",
                )
            }
            history.forEach { entry ->
                val origin = checkNotNull(entry.origin) { "${entry.what} has no origin" }
                origin.address shouldBe "192.0.2.20"
                origin.userAgent shouldBe "Firefox/131"
                origin.session shouldStartWith "issued:"
                entry.who?.subject shouldBe "ada"
            }

            // A subject's requests, and nothing outside the days asked for.
            auditor.outcome(auditEntries, In5(null, "test/hers-$run", null, null, null)).shouldBeOk().map { it.what } shouldBe
                listOf("requested", "awaiting-approval", "vote-refused")
            val yesterday = today.minusDays(1).toString()
            auditor.outcome(auditEntries, In5("ada", null, null, null, yesterday)).shouldBeOk().shouldBeEmpty()
            auditor.response(auditEntries, In5("ada", null, null, "last tuesday", null)).status shouldBe 422

            val csv = auditor.outcome(auditExport, In6("csv", "ada", null, null, null, null)).shouldBeOk()
            csv.lines().first() shouldStartWith "request,sequence,at"
            csv.lines().filter { it.isNotBlank() }.size shouldBe 6
            node.audit.exportsBy("aud") shouldBe 1
            node.calling("eve", "marketing").response(auditExport, In6("csv", "ada", null, null, null, null)).status shouldBe 403
            node.audit.exportsBy("eve") shouldBe 0
        }.shouldBeRight()
    }
}
