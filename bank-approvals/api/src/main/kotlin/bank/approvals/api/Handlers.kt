package bank.approvals.api

import arrow.core.Either
import arrow.core.flatMap
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import bank.approvals.domain.Asked
import bank.approvals.domain.Ending
import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import bank.approvals.domain.Proposal
import bank.approvals.domain.Refusal
import bank.approvals.domain.Request
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.approvals.domain.replay
import bank.approvals.protocol.Act
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.PageGuard
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.pages
import io.github.matthewjones372.pelican.pekko.handledNow
import java.time.Instant
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.pekko.handledOrFail
import java.time.LocalDate
import java.time.format.DateTimeParseException
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.metrics.otel.openTelemetry
import io.github.matthewjones372.pelican.pekko.request
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.propagation.TextMapGetter

/** Why approvals could not answer: nothing asked under that id, the request's own refusal, or no answer in time. */
sealed interface Unanswered {
    data class Refused(val refusal: Refusal) : Unanswered
    data class Unavailable(val message: String) : Unanswered
}

/** The requests, wherever in the cluster each lives. Each answer is the request's whole history. */
interface Approvals {
    /** A new request for [proposal], by [requester] from [origin], decided under the policy in force now. */
    fun ask(proposal: Proposal, requester: Person, origin: Origin): Either<Unanswered, Pair<RequestId, List<RequestEvent>>>

    fun act(id: RequestId, act: Act): Either<Unanswered, List<RequestEvent>>
}

/** Every request's history as the journal holds it now: what the pages' lists are drawn from. */
fun interface Histories {
    fun all(): Map<RequestId, List<RequestEvent>>
}

/**
 * How people sign in to the pages, and what verifies a caller: a bearer token, or a signed-in session (lark-bank spec
 * 0021). The app builds it from pelican-oidc against the identity provider.
 */
interface Signing {
    val endpoints: List<ServerEndpoint>
    val authenticator: Authenticator
    val guard: PageGuard
}

/**
 * The API over [approvals] and [audit], and the pages, signed in to through [signing]. [approvers] is every group the
 * policies in force name: whoever is in one, or is an auditor, reads the audit.
 */
fun approvalsApi(
    approvals: Approvals,
    audit: Audit,
    histories: Histories,
    signing: Signing,
    telemetry: OpenTelemetry = OpenTelemetry.noop(),
    approvers: () -> Set<String>,
) = api(
    endpoints = listOf(
        ask handledOrFail { body ->
            val signed = this[caller]
            val who = body.requestedBy?.let { named ->
                if (!Rules.speaksForOwner(signed.person)) {
                    return@handledOrFail forbidden(Problem("Only an owning service asks on someone else's behalf"))
                }
                Person(named.subject, named.name, emptySet())
            } ?: signed.person
            if (body.before == body.after) return@handledOrFail badRequest(Problem("before and after are the same: nothing would change"))
            val proposal = Proposal(body.kind, body.subject, body.title, body.before, body.after, body.facts, body.impact, body.link)
            approvals.ask(proposal, who, origin(signed, this[userAgent], this[forwardedFor]))
                .fold(::problem) { (id, history) -> ok(view(id, history)) }
        },
        read handledOrFail { id ->
            val who = this[caller].person
            approvals.act(RequestId(id), Act.Read).fold(::problem) { history ->
                val asked = replay(history).asked() ?: return@fold notFound(Problem("No request $id"))
                if (Rules.canRead(who, asked)) ok(view(RequestId(id), history)) else forbidden(Problem("Not on request $id"))
            }
        },
        approve handledOrFail { (id, body) ->
            val signed = this[caller]
            answer(approvals, id, Act.Approve(signed.person, body.hash, body.comment, origin(signed, this[userAgent], this[forwardedFor])))
        },
        reject handledOrFail { (id, body) ->
            val signed = this[caller]
            answer(approvals, id, Act.Reject(signed.person, body.comment, origin(signed, this[userAgent], this[forwardedFor])))
        },
        comment handledOrFail { (id, body) ->
            val signed = this[caller]
            answer(approvals, id, Act.Comment(signed.person, body.text, origin(signed, this[userAgent], this[forwardedFor])))
        },
        withdraw handledOrFail { id ->
            val signed = this[caller]
            answer(approvals, id, Act.Withdraw(signed.person, origin(signed, this[userAgent], this[forwardedFor])))
        },
        applied handledOrFail { (id, body) ->
            val signed = this[caller]
            if (!Rules.speaksForOwner(signed.person)) forbidden(Problem("Only the owning service says a change is applied"))
            else answer(approvals, id, Act.MarkApplied(signed.person, body.hash, origin(signed, this[userAgent], this[forwardedFor])))
        },
        applyFailed handledOrFail { (id, body) ->
            val signed = this[caller]
            if (!Rules.speaksForOwner(signed.person)) forbidden(Problem("Only the owning service says a change failed to apply"))
            else answer(approvals, id, Act.MarkApplyFailed(signed.person, body.reason, origin(signed, this[userAgent], this[forwardedFor])))
        },
        auditEntries handledOrFail { (person, subject, request, from, to) ->
            if (!Rules.canAudit(this[caller].person, approvers())) return@handledOrFail forbidden(Problem("Only approvers and auditors read the audit"))
            filter(person, subject, request, from, to).fold({ badRequest(it) }) { ok(audit.entries(it)) }
        },
        auditExport handledOrFail { (format, person, subject, request, from, to) ->
            val signed = this[caller]
            if (!Rules.canAudit(signed.person, approvers())) return@handledOrFail forbidden(Problem("Only approvers and auditors export the audit"))
            val kind = format ?: "csv"
            if (kind != "csv" && kind != "json") return@handledOrFail badRequest(Problem("An export is csv or json, not $kind"))
            filter(person, subject, request, from, to).fold({ badRequest(it) }) { asked ->
                val entries = audit.entries(asked)
                audit.exported(signed.person, origin(signed, this[userAgent], this[forwardedFor]), asked, kind, entries.size)
                ok(if (kind == "csv") csv(entries) else EXPORTS.writeValueAsString(entries))
            }
        },
        verify handledOrFail { _ ->
            if (!Rules.canAudit(this[caller].person, approvers())) forbidden(Problem("Only approvers and auditors verify the audit"))
            else ok(audit.verify())
        },
        requests handledOrFail { list ->
            val who = this[caller].person
            val chosen: (Request, Asked) -> Boolean = when (list) {
                "waiting" -> { request, asked -> request is Request.Waiting && Rules.mayApprove(who, asked, request.approvedBy) }
                "mine" -> { _, asked -> asked.requester.subject == who.subject }
                "recent" -> { _, asked -> Rules.canRead(who, asked) }
                else -> return@handledOrFail badRequest(Problem("No list is called $list: waiting, mine or recent"))
            }
            ok(
                histories.all().mapNotNull { (id, history) ->
                    val request = replay(history)
                    request.asked()?.takeIf { chosen(request, it) }?.let { summary(view(id, history), history.last().at) }
                }.sortedByDescending { it.first }.take(LISTED).map { it.second },
            )
        },
        health handledNow { _ -> "ok" },
        me handledNow { _ ->
            val who = this[caller].person
            Me(who.subject, who.name, who.groups.sorted())
        },
    ) + signing.endpoints,
    codecs = JacksonCodecs,
) {
    title = "Approvals"
    version = "0.1.0"
    // The pages beside the endpoints, which always win (Pelican 0059): signed in, or sent to sign in first, and never cached.
    pages = pages("ui").guardedBy(signing.guard)
    authenticate(bearer, signing.authenticator)
    // A server span per request, continuing the caller's trace (lark-bank spec 0024); outermost, so a refusal is in it.
    filter(openTelemetry(telemetry, incomingHeaders = pekkoHeaders))
}

/** An inbound `traceparent`, read off Pekko's own request: the one header Pelican's filter cannot read for itself. */
private val pekkoHeaders: TextMapGetter<Params> = object : TextMapGetter<Params> {
    override fun keys(carrier: Params): Iterable<String> = carrier.request.headers.map { it.lowercaseName() }

    override fun get(carrier: Params?, key: String): String? = carrier?.request?.getHeader(key)?.map { it.value() }?.orElse(null)
}

/** The most a list shows: the most recently moved. */
private const val LISTED = 200

private fun summary(view: RequestView, at: Instant): Pair<Instant, RequestSummary> = at to RequestSummary(
    view.id, view.state, view.kind, view.subject, view.title, view.requester, view.approvedBy.size, view.needed, at.toString(),
)

/**
 * The request after [act]. A vote the rules turned away is recorded, and answered as refused with its reason, so an
 * approver learns their vote did not count.
 */
private fun answer(approvals: Approvals, id: String, act: Act): Outcome<Problem, RequestView> =
    approvals.act(RequestId(id), act).fold(::problem) { history ->
        val last = history.lastOrNull()
        if (last is RequestEvent.VoteRefused && last.by == voter(act)) {
            refused(Problem("That vote was not counted: ${last.because}", last.because.name))
        } else {
            ok(view(RequestId(id), history))
        }
    }

/** Who is voting, when [act] is a vote. */
private fun voter(act: Act): Person? = when (act) {
    is Act.Approve -> act.by
    is Act.Reject -> act.by
    is Act.Ask, is Act.Comment, is Act.Withdraw, is Act.Supersede, is Act.MarkApplied, is Act.MarkApplyFailed, Act.Read -> null
}

/** An export's JSON, written as the API writes its own. */
private val EXPORTS = jacksonObjectMapper()

/** The auditor's filter, or why its days do not read. */
private fun filter(person: String?, subject: String?, request: String?, from: String?, to: String?): Either<Problem, AuditFilter> {
    fun day(text: String?): Either<Problem, LocalDate?> = when (text) {
        null -> Either.Right(null)
        else -> try {
            Either.Right(LocalDate.parse(text))
        } catch (_: DateTimeParseException) {
            Either.Left(Problem("$text is not a day: write it as 2026-10-01"))
        }
    }
    return day(from).flatMap { start -> day(to).map { end -> AuditFilter(person, subject, request, start, end) } }
}

private fun <T> problem(unanswered: Unanswered): Outcome<Problem, T> = when (unanswered) {
    is Unanswered.Unavailable -> unavailable(Problem(unanswered.message))
    is Unanswered.Refused -> when (val refusal = unanswered.refusal) {
        Refusal.NotAsked -> notFound(Problem("No such request", refusal.name()))
        Refusal.CommentRequired -> badRequest(Problem("A rejection or a comment needs words", refusal.name()))
        Refusal.NotOnTheRequest -> forbidden(Problem("Only the requester and its approvers comment", refusal.name()))
        Refusal.OnlyTheRequesterWithdraws -> forbidden(Problem("Only its requester withdraws a request", refusal.name()))
        Refusal.AskedDifferently, Refusal.NotWaiting, Refusal.NotAgreed, Refusal.NotYetDue ->
            refused(Problem("The request cannot take that now", refusal.name()))
        Refusal.NotWhatWasApproved ->
            refused(Problem("That is not the content approved: apply what was approved, or ask again", refusal.name()))
    }
}

private fun Refusal.name() = this::class.simpleName!!

private fun Request.asked(): Asked? = when (this) {
    Request.Unasked -> null
    is Request.Waiting -> asked
    is Request.Agreed -> asked
    is Request.Ended -> asked
}

private fun Person.view() = PersonView(subject, name)

/** The request as its history replays, with the history as its timeline. */
fun view(id: RequestId, history: List<RequestEvent>): RequestView {
    val request = replay(history)
    val asked = checkNotNull(request.asked()) { "request ${id.value} has no history" }
    val (state, approvedBy, automatically) = when (request) {
        Request.Unasked -> error("unreachable: an unasked request has no view")
        is Request.Waiting -> Triple("awaiting-approval", request.approvedBy, null)
        is Request.Agreed -> Triple("approved", request.approvedBy, request.automatically?.facts)
        is Request.Ended -> Triple(
            when (request.how) {
                Ending.Applied -> "applied"
                is Ending.Rejected -> "rejected"
                is Ending.Withdrawn -> "withdrawn"
                is Ending.Superseded -> "superseded"
                Ending.Expired -> "expired"
            },
            request.approvedBy,
            history.filterIsInstance<RequestEvent.AutoApproved>().firstOrNull()?.condition?.facts,
        )
    }
    val proposal = asked.proposal
    return RequestView(
        id = id.value, state = state, kind = proposal.kind, subject = proposal.subject, title = proposal.title,
        before = proposal.before, after = proposal.after, facts = proposal.facts, impact = proposal.impact,
        link = proposal.link, hash = asked.hash, requester = asked.requester.view(), needed = asked.policy.needed,
        approvers = asked.policy.approvers.sorted(), approvedBy = approvedBy.map { it.view() },
        policyVersion = asked.policyVersion, expiresAt = asked.expiresAt.toString(), approvedAutomatically = automatically,
        timeline = history.map(::entry),
    )
}

private fun entry(event: RequestEvent): Entry {
    val at = event.at.toString()
    return when (event) {
        is RequestEvent.Requested -> Entry("requested", event.asked.requester.view(), event.asked.proposal.title, at)
        is RequestEvent.AutoApproved -> Entry("approved-automatically", null, event.condition.facts.toString(), at)
        is RequestEvent.AwaitingApproval -> Entry("awaiting-approval", null, "${event.needed} of ${event.approvers.sorted()}", at)
        is RequestEvent.Approved -> Entry("approved", event.by.view(), event.comment, at)
        is RequestEvent.ApprovalGiven -> Entry("approval-given", null, event.approvers.joinToString(), at)
        is RequestEvent.Rejected -> Entry("rejected", event.by.view(), event.comment, at)
        is RequestEvent.Commented -> Entry("commented", event.by.view(), event.text, at)
        is RequestEvent.VoteRefused -> Entry("vote-refused", event.by.view(), event.because.name, at)
        is RequestEvent.Withdrawn -> Entry("withdrawn", event.by.view(), null, at)
        is RequestEvent.Superseded -> Entry("superseded", null, event.by.value, at)
        is RequestEvent.Expired -> Entry("expired", null, null, at)
        is RequestEvent.Applied -> Entry("applied", null, null, at)
        is RequestEvent.ApplyFailed -> Entry("apply-failed", null, event.reason, at)
    }
}
