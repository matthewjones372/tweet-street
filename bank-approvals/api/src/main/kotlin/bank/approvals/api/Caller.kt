package bank.approvals.api

import bank.approvals.domain.Asked
import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.optional
import java.util.Date

/** A bearer token from the bank's identity provider, as the bank takes it (lark-bank spec 0021). */
val bearer = bearerAuth(name = "approvals", description = "An access token from the bank's identity provider")

/** Someone signed in, and the session their token was issued to: who, on every act, and part of from where. */
data class Signed(val person: Person, val session: String)

val caller = authenticated(bearer) { id: Identity -> Signed(Person(id.subject, id.name ?: id.subject, id.groups), session(id)) }

/** The provider's session id when the token names one, else the token's own id, else when it was issued. */
private fun session(id: Identity): String =
    (id.claims["sid"] ?: id.claims["jti"])?.toString()
        ?: id.claims["iat"]?.let { issued -> "issued:" + ((issued as? Date)?.toInstant()?.epochSecond ?: issued) }.orEmpty()

val userAgent = headerParam<String>("User-Agent", description = "Recorded on the act, as part of where it came from").optional()

val forwardedFor = headerParam<String>(
    "X-Forwarded-For",
    description = "Set by the ingress: the address the request came from, recorded on the act",
).optional()

/**
 * Where an act came from: the caller's session, the first address the ingress forwarded (the client's; the service is
 * only reached through it), and the user agent.
 */
fun origin(signed: Signed, agent: String?, forwarded: String?): Origin =
    Origin(signed.session, forwarded?.substringBefore(',')?.trim().orEmpty(), agent.orEmpty())

/** Who may do what beyond what the domain decides: who may read a request, and who speaks for an owning service. */
object Rules {
    /** The owning services: they report a change applied, or not. */
    const val SERVICES = "services"

    /** Who reads and exports the audit, and never votes. */
    const val AUDITOR = "auditor"

    /** Everyone on the request may read it: its requester, anyone who may approve it, and the auditors and services. */
    fun canRead(who: Person, asked: Asked): Boolean =
        who.subject == asked.requester.subject ||
            who.groups.any { it in asked.policy.approvers || it == AUDITOR || it == SERVICES }

    fun speaksForOwner(who: Person): Boolean = SERVICES in who.groups

    /** Whether [who] may still approve a request waiting on [approvedBy]: in its groups, not its requester, not yet voted. */
    fun mayApprove(who: Person, asked: Asked, approvedBy: List<Person>): Boolean =
        who.subject != asked.requester.subject &&
            who.groups.any { it in asked.policy.approvers } &&
            approvedBy.none { it.subject == who.subject }

    /** The audit is read by every approver, of any kind the policies name, and by the auditors. */
    fun canAudit(who: Person, approvers: Set<String>): Boolean = who.groups.any { it == AUDITOR || it in approvers }
}
