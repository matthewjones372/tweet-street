package bank.approvals.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * What a request's events are in the journal and on the wire. Each mirrors a domain type; field numbers are
 * declaration order, so a field is added at the end and never moved. None is nullable and none a map: an absent text
 * is "", and facts are a list, so the bytes for one request never depend on a map's order.
 */

@Serializable
data class Person(val subject: String, val name: String, val groups: List<String>)

/** Where an act came from; "" for what nobody sent. Last on each event that has one, so older bytes still read. */
@Serializable
data class Origin(val session: String = "", val address: String = "", val userAgent: String = "")

/** Nobody: the `by` of an event the service records itself. */
val NOBODY = Person("", "", emptyList())

@Serializable
data class Fact(val key: String, val value: String)

@Serializable
data class Condition(val facts: List<Fact>)

@Serializable
data class Policy(
    val kind: String,
    val approvers: List<String>,
    val needed: Int,
    val expiresAfterMillis: Long,
    val autoApprove: List<Condition>,
)

@Serializable
data class Proposal(
    val kind: String,
    val subject: String,
    val title: String,
    val before: String,
    val after: String,
    val facts: List<Fact>,
    val impact: String,
    val link: String,
)

@Serializable
data class Asked(
    val id: String,
    val proposal: Proposal,
    val requester: Person,
    val policy: Policy,
    val policyVersion: String,
    val expiresAtMillis: Long,
)

sealed interface RequestEvent {
    @Serializable
    data class Requested(val asked: Asked, val atMillis: Long, val origin: Origin = Origin()) : RequestEvent

    @Serializable
    data class AutoApproved(val condition: Condition, val atMillis: Long) : RequestEvent

    @Serializable
    data class AwaitingApproval(val needed: Int, val approvers: List<String>, val atMillis: Long) : RequestEvent

    @Serializable
    data class Approved(val by: Person, val hash: String, val comment: String, val atMillis: Long, val origin: Origin = Origin()) :
        RequestEvent

    @Serializable
    data class ApprovalGiven(val approvers: List<String>, val atMillis: Long) : RequestEvent

    @Serializable
    data class Rejected(val by: Person, val comment: String, val atMillis: Long, val origin: Origin = Origin()) : RequestEvent

    @Serializable
    data class Commented(val by: Person, val text: String, val atMillis: Long, val origin: Origin = Origin()) : RequestEvent

    @Serializable
    data class VoteRefused(val by: Person, val because: String, val atMillis: Long, val origin: Origin = Origin()) : RequestEvent

    @Serializable
    data class Withdrawn(val by: Person, val atMillis: Long, val origin: Origin = Origin()) : RequestEvent

    @Serializable
    data class Superseded(val by: String, val atMillis: Long) : RequestEvent

    @Serializable
    data class Expired(val atMillis: Long) : RequestEvent

    @Serializable
    data class Applied(val atMillis: Long, val by: Person = NOBODY, val hash: String = "", val origin: Origin = Origin()) : RequestEvent

    @Serializable
    data class ApplyFailed(val reason: String, val atMillis: Long, val by: Person = NOBODY, val origin: Origin = Origin()) : RequestEvent
}

/** What a request is asked to do, without a time: the entity stamps it with its own clock. */
sealed interface Act {
    @Serializable
    @SerialName("AskAct")
    data class Ask(val proposal: Proposal, val requester: Person, val policy: Policy, val policyVersion: String, val origin: Origin = Origin()) :
        Act

    @Serializable
    @SerialName("ApproveAct")
    data class Approve(val by: Person, val hash: String, val comment: String, val origin: Origin = Origin()) : Act

    @Serializable
    @SerialName("RejectAct")
    data class Reject(val by: Person, val comment: String, val origin: Origin = Origin()) : Act

    @Serializable
    @SerialName("CommentAct")
    data class Comment(val by: Person, val text: String, val origin: Origin = Origin()) : Act

    @Serializable
    @SerialName("WithdrawAct")
    data class Withdraw(val by: Person, val origin: Origin = Origin()) : Act

    @Serializable
    @SerialName("SupersedeAct")
    data class Supersede(val by: String) : Act

    @Serializable
    @SerialName("MarkAppliedAct")
    data class MarkApplied(val by: Person, val hash: String, val origin: Origin = Origin()) : Act

    @Serializable
    @SerialName("MarkApplyFailedAct")
    data class MarkApplyFailed(val reason: String, val by: Person = NOBODY, val origin: Origin = Origin()) : Act

    /** Only reads: the request as it stands, and its history. */
    @Serializable
    @SerialName("ReadAct")
    data object Read : Act
}

/** A request's answer: its history, from which the receiver replays where it stands. */
@Serializable
data class History(val events: List<ByteArrayEvent>)

/** One event as the journal writes it, so a reply carries exactly the bytes the history holds. */
@Serializable
data class ByteArrayEvent(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is ByteArrayEvent && bytes.contentEquals(other.bytes)

    override fun hashCode() = bytes.contentHashCode()
}

/**
 * One request event as the journal keeps it: the event's own bytes, and its link in the request's hash chain, the
 * SHA-256 of the link before it and those bytes (lark-bank spec 0019). The bytes are kept as written, so the chain is
 * checked against exactly what is stored.
 */
@Serializable
data class Linked(val event: ByteArray, val link: String) {
    override fun equals(other: Any?) = other is Linked && event.contentEquals(other.event) && link == other.link

    override fun hashCode() = 31 * event.contentHashCode() + link.hashCode()
}

/** Why a request could not take what it was asked, by the domain's name for it. */
@Serializable
data class Refused(val refusal: String)

/** A subject's live request: what a new one supersedes. */
sealed interface SubjectEvent {
    @Serializable
    data class Claimed(val request: String) : SubjectEvent

    @Serializable
    data class Released(val request: String) : SubjectEvent
}
