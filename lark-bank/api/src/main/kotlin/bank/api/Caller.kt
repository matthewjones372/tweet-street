package bank.api

import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth

// Who is calling (bank spec 0021): verified before any handler runs, and every decision about what they may do made
// in Rules below, from the caller and facts the bank already holds.

/** The groups whose members are staff, as Pocket ID names them. */
val STAFF = setOf("support", "ops", "auditor", "admins")

data class Caller(
    val subject: String,
    val name: String,
    val groups: Set<String>,
    val actor: String? = null,
    /** When acting as [subject] ends: a signed-in page's session says so; a token with an actor does not. */
    val actingUntil: java.time.Instant? = null,
) {
    /** Whose data this request is about: the customer, even when someone else is acting as them. */
    val actingAs: String get() = subject

    val isImpersonating: Boolean get() = actor != null

    val isStaff: Boolean get() = groups.any { it in STAFF }
}

/**
 * The document's scheme: a bearer token from the bank's identity provider. A signed-in page sends its session cookie
 * instead, and the bank takes either.
 */
val bearer = bearerAuth(
    name = "bank",
    description = "An access token from the bank's identity provider; a signed-in page's session cookie also serves",
)

val caller = authenticated(bearer) { id: Identity ->
    Caller(id.subject, id.name ?: id.subject, id.groups, id.actor, id.claims["acting_until"] as? java.time.Instant)
}

/**
 * Which accounts someone in support may see, and for how long: each a request approved in Approvals (bank spec 0021),
 * kept by the bank until it expires.
 */
fun interface Grants {
    /** Whether [person] holds a grant to see [account] that has not yet expired. */
    fun supports(person: String, account: String): Boolean

    /** When [person]'s grant to act as [customer] ends, or null if they hold none that has not. */
    fun actingUntil(person: String, customer: String): java.time.Instant? = null

    /**
     * The approval whose grant let [who] make a request about [account]: acting as a customer, or seeing their
     * account; null when no grant was needed or none was held.
     */
    fun grantUsed(who: Caller, account: String?): String? = null

    companion object {
        /** Nobody holds any: support sees nothing. */
        val none: Grants = Grants { _, _ -> false }
    }
}

/** The questions bank-access is asked beside the bank's own rules (bank spec 0022's access-shadow). */
enum class Asked(val label: String) {
    ViewAccount("view-account"),
    PayFromAccount("pay-from-account"),
    ActAs("act-as"),
}

/**
 * The same question put to bank-access, with what the bank's own rule answered: compared there, never waited for here.
 * Until a rule is flipped to bank-access, the bank's answer is the one used.
 */
fun interface Shadow {
    fun compare(asked: Asked, who: Caller, target: String, local: Boolean)

    companion object {
        val none: Shadow = Shadow { _, _, _, _ -> }
    }
}

/** Every decision about what a caller may do, one line each, from the caller and what the bank already knows. */
class Rules(private val grants: Grants = Grants.none, private val shadow: Shadow = Shadow.none) {
    /** Its owner, or someone acting as them with a live grant; an auditor; or support with a grant for this account. */
    fun canView(who: Caller, account: String, owner: String): Boolean =
        ((owner == who.actingAs && mayActAs(who)) || "auditor" in who.groups || supports(who, account))
            .also { shadow.compare(Asked.ViewAccount, who, account, it) }

    fun canMove(who: Caller, account: String, owner: String): Boolean =
        (owner == who.actingAs && !who.isImpersonating).also { shadow.compare(Asked.PayFromAccount, who, account, it) }

    fun canOpen(who: Caller): Boolean = !who.isImpersonating

    /**
     * Whether the caller is who they say, or acting as them with a grant that still lives. Asked on every request,
     * not only when acting starts: a session minted for a grant says nothing once the grant is gone.
     */
    fun mayActAs(who: Caller): Boolean = when (val actor = who.actor) {
        null -> true
        else -> (grants.actingUntil(actor, who.subject) != null).also { shadow.compare(Asked.ActAs, who, who.subject, it) }
    }

    /** When [who] may act as [customer] until: someone in support, as themselves, with a live grant; else null. */
    fun actingUntil(who: Caller, customer: String): java.time.Instant? =
        if ("support" in who.groups && !who.isImpersonating) grants.actingUntil(who.subject, customer) else null

    /** A transfer is seen by whoever may see either end of it, each end an account and its owner. */
    fun canViewTransfer(who: Caller, from: Pair<String, String?>, to: Pair<String, String?>): Boolean =
        listOf(from, to).any { (account, owner) -> owner != null && canView(who, account, owner) } || canSeeOps(who)

    fun canSeeOps(who: Caller): Boolean = who.groups.any { it in setOf("ops", "admins", "auditor") }

    /** Who can see what, and why (bank spec 0022): the auditor's question, and ops' and admins' too. */
    fun canAudit(who: Caller): Boolean = canSeeOps(who) && !who.isImpersonating

    /** A payroll pays into many customers' accounts at once: the bank's own batch, run by ops. */
    fun canSendPayroll(who: Caller): Boolean = who.groups.any { it in setOf("ops", "admins") } && !who.isImpersonating

    /** Support sees an account as themselves, never while acting as someone else, and only while a grant lives. */
    private fun supports(who: Caller, account: String): Boolean =
        "support" in who.groups && !who.isImpersonating && grants.supports(who.subject, account)
}
