package bank.approvals.protocol

import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import bank.approvals.domain.RefusedBecause
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.events.v1.ApprovalEvent

/*
 * A request's events as the contract owning services read (lark-bank spec 0019, `bank.approval-events` in
 * lark-bank-events): each domain event to its Protobuf message. The journal's own encoding (Codecs.kt) changes on its
 * own; this one only ever grows.
 */

/**
 * What a request's published events carry besides themselves: which request, its kind and subject, and the hash of its
 * content, which an approval names so the owning service can check it.
 */
data class RequestHead(val id: RequestId, val kind: String, val subject: String, val contentHash: String)

/** The head a request's first event names: every request begins with its `Requested`. */
fun RequestEvent.Requested.head(): RequestHead = RequestHead(asked.id, asked.proposal.kind, asked.proposal.subject, asked.hash)

/** Request [head]'s event as published: [sequence] is its place in the request, from 1. */
fun RequestEvent.toContract(head: RequestHead, sequence: Long): ApprovalEvent {
    val record = ApprovalEvent.newBuilder()
        .setRequestId(head.id.value)
        .setSequence(sequence)
        .setAtMillis(at.toEpochMilli())
        .setKind(head.kind)
        .setSubject(head.subject)
    origin()?.let { record.setOrigin(it) }
    when (this) {
        is RequestEvent.Requested -> record.setRequested(requested())
        is RequestEvent.AutoApproved -> record.setAutoApproved(
            ApprovalEvent.AutoApproved.newBuilder().putAllCondition(condition.facts).setContentHash(head.contentHash),
        )
        is RequestEvent.AwaitingApproval ->
            record.setAwaitingApproval(ApprovalEvent.AwaitingApproval.newBuilder().setNeeded(needed).addAllApprovers(approvers.sorted()))
        is RequestEvent.Approved -> record.setApproved(
            ApprovalEvent.Approved.newBuilder().setBy(by.toContract()).setContentHash(hash).also { built -> comment?.let(built::setComment) },
        )
        is RequestEvent.ApprovalGiven ->
            record.setApprovalGiven(ApprovalEvent.ApprovalGiven.newBuilder().addAllApprovers(approvers).setContentHash(head.contentHash))
        is RequestEvent.Rejected -> record.setRejected(ApprovalEvent.Rejected.newBuilder().setBy(by.toContract()).setComment(comment))
        is RequestEvent.Commented -> record.setCommented(ApprovalEvent.Commented.newBuilder().setBy(by.toContract()).setText(text))
        is RequestEvent.VoteRefused ->
            record.setVoteRefused(ApprovalEvent.VoteRefused.newBuilder().setBy(by.toContract()).setBecause(because.toContract()))
        is RequestEvent.Withdrawn -> record.setWithdrawn(ApprovalEvent.Withdrawn.newBuilder().setBy(by.toContract()))
        is RequestEvent.Superseded -> record.setSuperseded(ApprovalEvent.Superseded.newBuilder().setByRequestId(by.value))
        is RequestEvent.Expired -> record.setExpired(ApprovalEvent.Expired.getDefaultInstance())
        is RequestEvent.Applied -> record.setApplied(
            ApprovalEvent.Applied.newBuilder().setContentHash(hash).also { built -> by?.let { built.setBy(it.toContract()) } },
        )
        is RequestEvent.ApplyFailed -> record.setApplyFailed(
            ApprovalEvent.ApplyFailed.newBuilder().setReason(reason).also { built -> by?.let { built.setBy(it.toContract()) } },
        )
    }
    return record.build()
}

private fun RequestEvent.Requested.requested(): ApprovalEvent.Requested {
    val proposal = asked.proposal
    return ApprovalEvent.Requested.newBuilder()
        .setTitle(proposal.title)
        .setBefore(proposal.before)
        .setAfter(proposal.after)
        .putAllFacts(proposal.facts)
        .also { built -> proposal.impact?.let(built::setImpact) }
        .also { built -> proposal.link?.let(built::setLink) }
        .setRequester(asked.requester.toContract())
        .setContentHash(asked.hash)
        .setPolicyVersion(asked.policyVersion)
        .addAllApprovers(asked.policy.approvers.sorted())
        .setNeeded(asked.policy.needed)
        .setExpiresAtMillis(asked.expiresAt.toEpochMilli())
        .build()
}

/** Where the act came from, for the events a person causes; null for what the service records itself. */
private fun RequestEvent.origin(): ApprovalEvent.Origin? = when (this) {
    is RequestEvent.Requested -> origin
    is RequestEvent.Approved -> origin
    is RequestEvent.Rejected -> origin
    is RequestEvent.Commented -> origin
    is RequestEvent.VoteRefused -> origin
    is RequestEvent.Withdrawn -> origin
    is RequestEvent.Applied -> origin
    is RequestEvent.ApplyFailed -> origin
    is RequestEvent.AutoApproved, is RequestEvent.AwaitingApproval, is RequestEvent.ApprovalGiven, is RequestEvent.Superseded,
    is RequestEvent.Expired -> null
}?.takeIf { it != Origin.NONE }?.let {
    ApprovalEvent.Origin.newBuilder().setSession(it.session).setAddress(it.address).setUserAgent(it.userAgent).build()
}

private fun Person.toContract(): ApprovalEvent.Person =
    ApprovalEvent.Person.newBuilder().setSubject(subject).setName(name).addAllGroups(groups.sorted()).build()

private fun RefusedBecause.toContract(): ApprovalEvent.Refusal = when (this) {
    RefusedBecause.OwnRequest -> ApprovalEvent.Refusal.REFUSAL_OWN_REQUEST
    RefusedBecause.AlreadyApproved -> ApprovalEvent.Refusal.REFUSAL_ALREADY_APPROVED
    RefusedBecause.NotAnApprover -> ApprovalEvent.Refusal.REFUSAL_NOT_AN_APPROVER
    RefusedBecause.Ended -> ApprovalEvent.Refusal.REFUSAL_ENDED
    RefusedBecause.NotWhatWasAsked -> ApprovalEvent.Refusal.REFUSAL_NOT_WHAT_WAS_ASKED
}
