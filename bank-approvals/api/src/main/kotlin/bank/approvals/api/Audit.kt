package bank.approvals.api

import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import bank.approvals.domain.RequestEvent
import java.time.LocalDate

/** Which entries an auditor asks for: any of a person, a subject and a request, within days, all optional. */
data class AuditFilter(
    val person: String? = null,
    val subject: String? = null,
    val request: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/** The audit as the journal keeps it: every request's events, their chains, and the nightly digests of their heads. */
interface Audit {
    /** Every event [filter] matches, oldest first. */
    fun entries(filter: AuditFilter): List<AuditEntry>

    fun verify(): Verification

    /** That [by] exported [rows] entries matching [filter] as [format], from [origin]: an export is itself on the record. */
    fun exported(by: Person, origin: Origin, filter: AuditFilter, format: String, rows: Int)
}

/** Request [request]'s event [event], its [sequence]th, of [kind] about [subject], as the auditor reads it. */
fun auditEntry(request: String, sequence: Long, kind: String, subject: String, event: RequestEvent): AuditEntry {
    fun entry(what: String, who: Person?, text: String?, hash: String? = null, origin: Origin? = null) = AuditEntry(
        request, sequence, event.at.toString(), kind, subject, what, who?.let { PersonView(it.subject, it.name) }, text, hash,
        origin?.takeIf { it != Origin.NONE }?.let { OriginView(it.session, it.address, it.userAgent) },
    )
    return when (event) {
        is RequestEvent.Requested -> entry("requested", event.asked.requester, event.asked.proposal.title, event.asked.hash, event.origin)
        is RequestEvent.AutoApproved -> entry("approved-automatically", null, event.condition.facts.toString())
        is RequestEvent.AwaitingApproval -> entry("awaiting-approval", null, "${event.needed} of ${event.approvers.sorted()}")
        is RequestEvent.Approved -> entry("approved", event.by, event.comment, event.hash, event.origin)
        is RequestEvent.ApprovalGiven -> entry("approval-given", null, event.approvers.joinToString())
        is RequestEvent.Rejected -> entry("rejected", event.by, event.comment, origin = event.origin)
        is RequestEvent.Commented -> entry("commented", event.by, event.text, origin = event.origin)
        is RequestEvent.VoteRefused -> entry("vote-refused", event.by, event.because.name, origin = event.origin)
        is RequestEvent.Withdrawn -> entry("withdrawn", event.by, null, origin = event.origin)
        is RequestEvent.Superseded -> entry("superseded", null, event.by.value)
        is RequestEvent.Expired -> entry("expired", null, null)
        is RequestEvent.Applied -> entry("applied", event.by, null, event.hash.ifEmpty { null }, event.origin)
        is RequestEvent.ApplyFailed -> entry("apply-failed", event.by, event.reason, origin = event.origin)
    }
}

private val COLUMNS = listOf(
    "request", "sequence", "at", "kind", "subject", "what", "who", "who_name", "text", "hash", "session", "address", "user_agent",
)

/** [entries] as CSV (RFC 4180): a header, then one line each, every field quoted. */
fun csv(entries: List<AuditEntry>): String = buildString {
    appendLine(COLUMNS.joinToString(","))
    entries.forEach { e ->
        val fields = listOf(
            e.request, e.sequence.toString(), e.at, e.kind, e.subject, e.what, e.who?.subject, e.who?.name, e.text, e.hash,
            e.origin?.session, e.origin?.address, e.origin?.userAgent,
        )
        appendLine(fields.joinToString(",") { "\"" + it.orEmpty().replace("\"", "\"\"") + "\"" })
    }
}
