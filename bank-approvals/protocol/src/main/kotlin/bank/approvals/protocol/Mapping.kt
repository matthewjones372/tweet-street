package bank.approvals.protocol

import bank.approvals.domain.Asked
import bank.approvals.domain.Condition
import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import bank.approvals.domain.Policy
import bank.approvals.domain.Proposal
import bank.approvals.domain.RefusedBecause
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import java.time.Duration
import java.time.Instant
import bank.approvals.protocol.wire.Asked as WireAsked
import bank.approvals.protocol.wire.Condition as WireCondition
import bank.approvals.protocol.wire.Fact as WireFact
import bank.approvals.protocol.wire.NOBODY
import bank.approvals.protocol.wire.Origin as WireOrigin
import bank.approvals.protocol.wire.Person as WirePerson
import bank.approvals.protocol.wire.Policy as WirePolicy
import bank.approvals.protocol.wire.Proposal as WireProposal
import bank.approvals.protocol.wire.RequestEvent as WireEvent

// Every crossing between the domain and its wire shapes, by hand: each field is named once each way, so a field
// added to one side and not the other is a compile error here, as kimney would make it in lark-bank.

internal fun Person.toWire() = WirePerson(subject, name, groups.sorted())

internal fun WirePerson.toDomain() = Person(subject, name, groups.toSet())

internal fun Map<String, String>.toFacts() = toSortedMap().map { (key, value) -> WireFact(key, value) }

internal fun List<WireFact>.toMap(): Map<String, String> = associate { it.key to it.value }

internal fun Condition.toWire() = WireCondition(facts.toFacts())

internal fun WireCondition.toDomain() = Condition(facts.toMap())

internal fun Policy.toWire() =
    WirePolicy(kind, approvers.sorted(), needed, expiresAfter.toMillis(), autoApprove.map { it.toWire() })

internal fun WirePolicy.toDomain() =
    Policy(kind, approvers.toSet(), needed, Duration.ofMillis(expiresAfterMillis), autoApprove.map { it.toDomain() })

internal fun Proposal.toWire() =
    WireProposal(kind, subject, title, before, after, facts.toFacts(), impact.orEmpty(), link.orEmpty())

internal fun WireProposal.toDomain() =
    Proposal(kind, subject, title, before, after, facts.toMap(), impact.ifEmpty { null }, link.ifEmpty { null })

internal fun Asked.toWire() =
    WireAsked(id.value, proposal.toWire(), requester.toWire(), policy.toWire(), policyVersion, expiresAt.toEpochMilli())

internal fun WireAsked.toDomain() = Asked(
    RequestId(id), proposal.toDomain(), requester.toDomain(), policy.toDomain(), policyVersion,
    Instant.ofEpochMilli(expiresAtMillis),
)

internal fun Origin.toWire() = WireOrigin(session, address, userAgent)

internal fun WireOrigin.toDomain() = Origin(session, address, userAgent)

private fun Person?.orNobody() = this?.toWire() ?: NOBODY

/** Nobody's for an event the service recorded itself, or one recorded before it said who. */
private fun WirePerson.orNobody(): Person? = if (subject.isEmpty()) null else toDomain()

private fun Instant.millis() = toEpochMilli()

private fun instant(millis: Long) = Instant.ofEpochMilli(millis)

internal fun RequestEvent.toWire(): WireEvent = when (this) {
    is RequestEvent.Requested -> WireEvent.Requested(asked.toWire(), at.millis(), origin.toWire())
    is RequestEvent.AutoApproved -> WireEvent.AutoApproved(condition.toWire(), at.millis())
    is RequestEvent.AwaitingApproval -> WireEvent.AwaitingApproval(needed, approvers.sorted(), at.millis())
    is RequestEvent.Approved -> WireEvent.Approved(by.toWire(), hash, comment.orEmpty(), at.millis(), origin.toWire())
    is RequestEvent.ApprovalGiven -> WireEvent.ApprovalGiven(approvers, at.millis())
    is RequestEvent.Rejected -> WireEvent.Rejected(by.toWire(), comment, at.millis(), origin.toWire())
    is RequestEvent.Commented -> WireEvent.Commented(by.toWire(), text, at.millis(), origin.toWire())
    is RequestEvent.VoteRefused -> WireEvent.VoteRefused(by.toWire(), because.name, at.millis(), origin.toWire())
    is RequestEvent.Withdrawn -> WireEvent.Withdrawn(by.toWire(), at.millis(), origin.toWire())
    is RequestEvent.Superseded -> WireEvent.Superseded(by.value, at.millis())
    is RequestEvent.Expired -> WireEvent.Expired(at.millis())
    is RequestEvent.Applied -> WireEvent.Applied(at.millis(), by.orNobody(), hash, origin.toWire())
    is RequestEvent.ApplyFailed -> WireEvent.ApplyFailed(reason, at.millis(), by.orNobody(), origin.toWire())
}

internal fun WireEvent.toDomain(): RequestEvent = when (this) {
    is WireEvent.Requested -> RequestEvent.Requested(asked.toDomain(), instant(atMillis), origin.toDomain())
    is WireEvent.AutoApproved -> RequestEvent.AutoApproved(condition.toDomain(), instant(atMillis))
    is WireEvent.AwaitingApproval -> RequestEvent.AwaitingApproval(needed, approvers.toSet(), instant(atMillis))
    is WireEvent.Approved -> RequestEvent.Approved(by.toDomain(), hash, comment.ifEmpty { null }, instant(atMillis), origin.toDomain())
    is WireEvent.ApprovalGiven -> RequestEvent.ApprovalGiven(approvers, instant(atMillis))
    is WireEvent.Rejected -> RequestEvent.Rejected(by.toDomain(), comment, instant(atMillis), origin.toDomain())
    is WireEvent.Commented -> RequestEvent.Commented(by.toDomain(), text, instant(atMillis), origin.toDomain())
    is WireEvent.VoteRefused ->
        RequestEvent.VoteRefused(by.toDomain(), RefusedBecause.valueOf(because), instant(atMillis), origin.toDomain())
    is WireEvent.Withdrawn -> RequestEvent.Withdrawn(by.toDomain(), instant(atMillis), origin.toDomain())
    is WireEvent.Superseded -> RequestEvent.Superseded(RequestId(by), instant(atMillis))
    is WireEvent.Expired -> RequestEvent.Expired(instant(atMillis))
    is WireEvent.Applied -> RequestEvent.Applied(instant(atMillis), by.orNobody(), hash, origin.toDomain())
    is WireEvent.ApplyFailed -> RequestEvent.ApplyFailed(reason, instant(atMillis), by.orNobody(), origin.toDomain())
}
