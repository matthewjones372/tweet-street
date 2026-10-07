package bank.approvals.domain

import java.security.MessageDigest

@JvmInline
value class RequestId(val value: String)

/** Someone signed in: the identity provider's subject, the name they gave, and the groups it put them in. */
data class Person(val subject: String, val name: String, val groups: Set<String>)

/**
 * One change to a [subject], as the service that owns it asks for approval of it: what it is [before] and would be
 * [after], as text an approver reads, and [facts] a policy's auto-approval is matched on. Approvals never parses any
 * of it: the owning service knows what its change means. [impact] and [link] are shown beside the diff, as sent.
 */
data class Proposal(
    val kind: String,
    val subject: String,
    val title: String,
    val before: String,
    val after: String,
    val facts: Map<String, String> = emptyMap(),
    val impact: String? = null,
    val link: String? = null,
) {
    /**
     * What is approved: the SHA-256 of [before], [after] and [facts], as hex. Each vote is cast on it and the owning
     * service acts only if it still holds it, so a change swapped after the votes is refused rather than applied.
     */
    val contentHash: String by lazy {
        val facts = facts.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key=$value" }
        sha256("before\u0000$before\u0000after\u0000$after\u0000facts\u0000$facts")
    }
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
