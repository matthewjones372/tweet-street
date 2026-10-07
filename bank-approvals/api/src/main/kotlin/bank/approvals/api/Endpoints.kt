package bank.approvals.api

import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam

// ---- bodies ----

/** One change asked about, as the owning service sends it (lark-bank spec 0019). */
data class NewRequest(
    val kind: String,
    val subject: String,
    val title: String,
    val before: String,
    val after: String,
    val facts: Map<String, String> = emptyMap(),
    val impact: String? = null,
    val link: String? = null,
    /**
     * Whose change it is, when an owning service asks for the person who made it: then it is their request, and they
     * may not approve it. Only a caller in `services` may name someone else.
     */
    val requestedBy: Requester? = null,
)

/** The person an owning service asks on behalf of, as the identity provider named them to it. */
data class Requester(val subject: String, val name: String)

/** An approval of the content whose hash is [hash]: what the approver was shown. */
data class ApproveBody(val hash: String, val comment: String? = null)

data class RejectBody(val comment: String)

data class CommentBody(val text: String)

data class ApplyFailedBody(val reason: String)

/** The owning service applied the content whose hash is [hash]: refused unless it is the content approved. */
data class AppliedBody(val hash: String)

// ---- views ----

data class PersonView(val subject: String, val name: String)

/** One act on the request's timeline, in order: who, what, and when by the server's clock. */
data class Entry(val what: String, val who: PersonView?, val text: String?, val at: String)

data class RequestView(
    val id: String,
    /** requested, awaiting-approval, approved, applied, rejected, withdrawn, superseded or expired. */
    val state: String,
    val kind: String,
    val subject: String,
    val title: String,
    val before: String,
    val after: String,
    val facts: Map<String, String>,
    val impact: String?,
    val link: String?,
    /** What an approval is cast on, and the owning service checks before it acts. */
    val hash: String,
    val requester: PersonView,
    val needed: Int,
    val approvers: List<String>,
    val approvedBy: List<PersonView>,
    val policyVersion: String,
    val expiresAt: String,
    /** The policy's condition that approved it with nobody looking, when one did. */
    val approvedAutomatically: Map<String, String>?,
    val timeline: List<Entry>,
)

/** One request in a list: enough to choose it, and where it stands. */
data class RequestSummary(
    val id: String,
    val state: String,
    val kind: String,
    val subject: String,
    val title: String,
    val requester: PersonView,
    val approvals: Int,
    val needed: Int,
    /** When it last moved, by the server's clock. */
    val at: String,
)

/** Who is signed in, as the pages greet them. */
data class Me(val subject: String, val name: String, val groups: List<String>)

/** Every refusal says why; [refusal] names the domain's reason, when there is one. */
data class Problem(val message: String, val refusal: String? = null)

val notFound = errorJson<Problem>(404, "No request with that id")
val refused = errorJson<Problem>(409, "The request cannot take that now: it says why")
val badRequest = errorJson<Problem>(422, "Not something a request can be asked")
val forbidden = errorJson<Problem>(403, "Not on this request")
val unavailable = errorJson<Problem>(503, "Approvals could not answer in time; asking again is safe")

// ---- endpoints ----

val requestId = pathParam<String>("requestId", description = "The request's id, as asking for it answered")

val ask = endpoint(jsonBody<NewRequest>()) {
    post("requests")
    authenticatedBy(caller)
    header(userAgent, forwardedFor)
    summary = "Ask for approval of one change; the caller is its requester, or the person an owning service names"
    json<RequestView>(status = 201).orFail(badRequest, forbidden, unavailable)
}

val read = endpoint(requestId) {
    get("requests" / requestId)
    authenticatedBy(caller)
    summary = "A request, its diff and its whole timeline"
    json<RequestView>().orFail(notFound, forbidden, unavailable)
}

val approve = endpoint(requestId, jsonBody<ApproveBody>()) {
    post("requests" / requestId / "approve")
    authenticatedBy(caller)
    header(userAgent, forwardedFor)
    summary = "Approve the content with this hash; a vote the rules turn away is recorded, and answered as refused"
    json<RequestView>().orFail(notFound, refused, unavailable)
}

val reject = endpoint(requestId, jsonBody<RejectBody>()) {
    post("requests" / requestId / "reject")
    authenticatedBy(caller)
    header(userAgent, forwardedFor)
    summary = "Reject it, with a reason, which ends it"
    json<RequestView>().orFail(notFound, refused, badRequest, unavailable)
}

val comment = endpoint(requestId, jsonBody<CommentBody>()) {
    post("requests" / requestId / "comment")
    authenticatedBy(caller)
    header(userAgent, forwardedFor)
    summary = "Comment without voting, on the request's thread"
    json<RequestView>().orFail(notFound, refused, forbidden, badRequest, unavailable)
}

val withdraw = endpoint(requestId) {
    post("requests" / requestId / "withdraw")
    authenticatedBy(caller)
    header(userAgent, forwardedFor)
    summary = "Withdraw it, by its requester, while it waits"
    json<RequestView>().orFail(notFound, refused, forbidden, unavailable)
}

val applied = endpoint(requestId, jsonBody<AppliedBody>()) {
    post("requests" / requestId / "applied")
    authenticatedBy(caller)
    header(userAgent, forwardedFor)
    summary = "The owning service applied the content with this hash, which must be the content approved: the end of the request"
    json<RequestView>().orFail(notFound, refused, forbidden, unavailable)
}

val applyFailed = endpoint(requestId, jsonBody<ApplyFailedBody>()) {
    post("requests" / requestId / "apply-failed")
    authenticatedBy(caller)
    header(userAgent, forwardedFor)
    summary = "The owning service could not apply it, and why; it is asked again"
    json<RequestView>().orFail(notFound, refused, forbidden, unavailable)
}

// ---- the audit (lark-bank spec 0019) ----

data class OriginView(val session: String, val address: String, val userAgent: String)

/** One event of one request, as the auditor reads it: whose request, what happened, who, from where, and when. */
data class AuditEntry(
    val request: String,
    val sequence: Long,
    val at: String,
    val kind: String,
    val subject: String,
    val what: String,
    val who: PersonView?,
    val text: String?,
    /** The content a vote or an applied was cast on. */
    val hash: String?,
    val origin: OriginView?,
)

/** An event whose chain does not hold, and why. */
data class Broken(val request: String, val sequence: Long, val why: String)

/** What walking every chain found: how much was checked, and the first broken event of each chain that breaks. */
data class Verification(val requests: Int, val events: Int, val digests: Int, val broken: List<Broken>)

val personQuery = queryParam<String>("person", description = "Only what this subject asked for, voted on or said").optional()
val subjectQuery = queryParam<String>("subject", description = "Only requests about this subject").optional()
val requestQuery = queryParam<String>("request", description = "Only this request").optional()
val fromQuery = queryParam<String>("from", description = "From this day (UTC), as 2026-10-01").optional()
val toQuery = queryParam<String>("to", description = "To this day (UTC), inclusive").optional()
val formatQuery = queryParam<String>("format", description = "csv or json").optional()

val auditEntries = endpoint(personQuery, subjectQuery, requestQuery, fromQuery, toQuery) {
    get("audit" / "entries")
    authenticatedBy(caller)
    summary = "Every event that matches, oldest first: a request's timeline, a person's history, a subject's"
    json<List<AuditEntry>>().orFail(forbidden, badRequest)
}

val auditExport = endpoint(formatQuery, personQuery, subjectQuery, requestQuery, fromQuery, toQuery) {
    get("audit" / "export")
    authenticatedBy(caller)
    header(userAgent, forwardedFor)
    summary = "The same entries as a file, CSV or JSON; the export is itself recorded, with who and from where"
    text().orFail(forbidden, badRequest)
}

val verify = endpoint {
    get("audit" / "verify")
    authenticatedBy(caller)
    summary = "Walk every request's hash chain, and every nightly digest of their heads, and name what does not match"
    json<Verification>().orFail(forbidden)
}

// ---- the pages' lists (lark-bank spec 0019) ----

val listQuery = queryParam<String>(
    "list",
    description = "waiting: requests the caller may approve and has not; mine: asked by the caller; recent: everything they may read",
)

val requests = endpoint(listQuery) {
    get("requests")
    authenticatedBy(caller)
    summary = "Requests in one of three lists, the most recently moved first"
    json<List<RequestSummary>>().orFail(badRequest)
}

/** Up and answering: what Kubernetes' probes ask, with no caller. */
val health = endpoint {
    get("health")
    summary = "Answers ok while this node serves"
    text()
}

val me = endpoint {
    get("me")
    authenticatedBy(caller)
    summary = "Who is signed in"
    json<Me>()
}
