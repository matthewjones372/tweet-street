package bank.access.sync

import bank.access.fga.Tuple
import bank.events.v1.AccountEvent
import bank.events.v1.ApprovalEvent
import bank.events.v1.ApprovalEvent.EventCase
import java.time.Duration
import java.time.Instant

// What each recorded fact means to the model (lark-bank spec 0022): pure, so a rebuild from offset zero writes exactly
// what the first reading did.

const val ACCOUNTS = "bank.account-events"
const val APPROVALS = "bank.approval-events"

/** The bank itself, the one object ops and auditors hang from. */
const val BANK = "bank:lark"

/** The groups the estate's model names (lark-bank spec 0022), emptied in the store if Pocket ID loses one. */
val KNOWN_GROUPS = setOf("risk", "support", "ops", "auditor", "admins")

/** Who runs and audits the bank: Pocket ID's groups, wired to it once. */
val wiring = listOf(
    Tuple("group:ops#member", "ops", BANK),
    Tuple("group:auditor#member", "auditor", BANK),
)

/** An account opened is its owner's, and the bank's; nothing else an account does changes who may see it. */
fun AccountEvent.relationships(): List<Tuple> = when (eventCase) {
    AccountEvent.EventCase.OPENED -> listOf(
        Tuple("person:${opened.owner}", "owner", "account:$accountId"),
        Tuple(BANK, "bank", "account:$accountId"),
    )
    else -> emptyList()
}

/** The kind of request a grant is in bank-approvals: the bank's (lark-bank spec 0021). */
const val GRANT_KIND = "bank.access-grant"

/** How long a grant lasts when it does not say, as the bank reads it. */
private val USUAL_GRANT: Duration = Duration.ofMinutes(30)

/**
 * Support's grants as bank-approvals publishes them: each request's facts kept from its `Requested`, the time it was
 * approved from its `ApprovalGiven`, and the relationship it gives written on `Applied`, once the bank, which owns
 * what a grant may be, has applied it. Expiry runs from approval, as the bank's does.
 */
class Grants {
    private class Asked(val facts: Map<String, String>, val hash: String, var approved: Instant? = null)

    private val asked = HashMap<String, Asked>()

    /** The relationship [event] completes, or null when it completes none. */
    fun heard(event: ApprovalEvent): Tuple? {
        if (event.kind != GRANT_KIND) return null
        val id = event.requestId
        return when (event.eventCase) {
            EventCase.REQUESTED -> {
                asked[id] = Asked(event.requested.factsMap, event.requested.contentHash)
                null
            }
            EventCase.APPROVAL_GIVEN, EventCase.AUTO_APPROVED -> {
                asked[id]?.approved = Instant.ofEpochMilli(event.atMillis)
                null
            }
            EventCase.APPLIED -> asked.remove(id)?.takeIf { it.hash == event.applied.contentHash }?.let(::given)
            EventCase.REJECTED, EventCase.WITHDRAWN, EventCase.SUPERSEDED, EventCase.EXPIRED -> {
                asked.remove(id)
                null
            }
            // The rest change nothing here, and a case added after this was built arrives unset.
            else -> null
        }
    }

    private fun given(grant: Asked): Tuple? {
        val facts = grant.facts
        val person = facts["person"] ?: return null
        val approved = grant.approved ?: return null
        val lasting = facts["for"]?.let(::duration) ?: USUAL_GRANT
        val expires = approved.plus(lasting)
        return when (facts["access"]) {
            "view" -> facts["account"]?.let { Tuple("person:$person", "supporter", "account:$it", expires) }
            "act-as" -> facts["customer"]?.let { Tuple("person:$person", "impersonator", "person:$it", expires) }
            else -> null
        }
    }
}

/** A grant's `for`, or null if it is not a duration; the bank refused such a grant, so none is applied. */
private fun duration(text: String): Duration? = try {
    Duration.parse(text)
} catch (_: java.time.format.DateTimeParseException) {
    null
}
