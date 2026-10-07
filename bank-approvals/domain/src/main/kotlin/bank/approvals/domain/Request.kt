package bank.approvals.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant

// A request for approval as a saga (lark-bank spec 0019): each step an event, the next read off the state. Every act
// is an event, a vote the rules turn away included, so the history is the record and nothing is ever edited.

/** What was asked, fixed when it was asked: what every later vote and act is checked against. */
data class Asked(
    val id: RequestId,
    val proposal: Proposal,
    val requester: Person,
    val policy: Policy,
    val policyVersion: String,
    val expiresAt: Instant,
) {
    val hash: String get() = proposal.contentHash
}

/**
 * Where an act came from (lark-bank spec 0019): the signed-in session, the address the ingress forwarded, and the user
 * agent, as the request carried them. The time is never the caller's: every event's is the server's.
 */
data class Origin(val session: String, val address: String, val userAgent: String) {
    companion object {
        /** Nobody's: what an act the service takes itself, or one recorded before origins were, carries. */
        val NONE = Origin("", "", "")
    }
}

sealed interface RequestEvent {
    val at: Instant

    data class Requested(val asked: Asked, override val at: Instant, val origin: Origin = Origin.NONE) : RequestEvent

    /** Nobody need look: the policy's [condition] matched the facts. Still a request, recorded as any other. */
    data class AutoApproved(val condition: Condition, override val at: Instant) : RequestEvent

    data class AwaitingApproval(val needed: Int, val approvers: Set<String>, override val at: Instant) : RequestEvent

    /** [by] approved the content whose hash was [hash]. */
    data class Approved(val by: Person, val hash: String, val comment: String?, override val at: Instant, val origin: Origin = Origin.NONE) :
        RequestEvent

    /** Enough distinct people approved: the owning service may apply it. */
    data class ApprovalGiven(val approvers: List<String>, override val at: Instant) : RequestEvent

    data class Rejected(val by: Person, val comment: String, override val at: Instant, val origin: Origin = Origin.NONE) : RequestEvent

    data class Commented(val by: Person, val text: String, override val at: Instant, val origin: Origin = Origin.NONE) : RequestEvent

    /** A vote the rules turned away, and why: the attempts matter to an audit as much as what succeeded. */
    data class VoteRefused(val by: Person, val because: RefusedBecause, override val at: Instant, val origin: Origin = Origin.NONE) :
        RequestEvent

    data class Withdrawn(val by: Person, override val at: Instant, val origin: Origin = Origin.NONE) : RequestEvent

    data class Superseded(val by: RequestId, override val at: Instant) : RequestEvent

    data class Expired(override val at: Instant) : RequestEvent

    /** The owning service applied the content whose hash is [hash], the approved one; [by] is the service that said so. */
    data class Applied(override val at: Instant, val by: Person? = null, val hash: String = "", val origin: Origin = Origin.NONE) :
        RequestEvent

    /** The owning service could not apply it; it is asked again, and the request stays agreed. */
    data class ApplyFailed(val reason: String, override val at: Instant, val by: Person? = null, val origin: Origin = Origin.NONE) :
        RequestEvent
}

enum class RefusedBecause {
    /** Nobody approves their own change. */
    OwnRequest,

    /** Nobody counts twice. */
    AlreadyApproved,

    /** Not in any group the policy names. */
    NotAnApprover,

    /** The request has already been decided, withdrawn or ended. */
    Ended,

    /** The vote was cast on content other than what was asked: the change moved under it. */
    NotWhatWasAsked,
}

/** How a request ended. */
sealed interface Ending {
    data object Applied : Ending
    data class Rejected(val by: Person, val comment: String) : Ending
    data class Withdrawn(val by: Person) : Ending
    data class Superseded(val by: RequestId) : Ending
    data object Expired : Ending
}

sealed interface Request {
    data object Unasked : Request

    /** Waiting on people: who has approved so far, in order. */
    data class Waiting(val asked: Asked, val approvedBy: List<Person>) : Request

    /** Approved, by people or by [automatically], and waiting for the owning service to say it is applied. */
    data class Agreed(val asked: Asked, val approvedBy: List<Person>, val automatically: Condition?) : Request

    data class Ended(val asked: Asked, val approvedBy: List<Person>, val how: Ending) : Request
}

sealed interface RequestCommand {
    val at: Instant

    data class Ask(
        val id: RequestId,
        val proposal: Proposal,
        val requester: Person,
        val policies: Policies,
        override val at: Instant,
        val origin: Origin = Origin.NONE,
    ) : RequestCommand

    /** [hash] is the content the approver was shown. */
    data class Approve(
        val by: Person,
        val hash: String,
        val comment: String? = null,
        override val at: Instant,
        val origin: Origin = Origin.NONE,
    ) : RequestCommand

    data class Reject(val by: Person, val comment: String, override val at: Instant, val origin: Origin = Origin.NONE) : RequestCommand

    data class Comment(val by: Person, val text: String, override val at: Instant, val origin: Origin = Origin.NONE) : RequestCommand

    data class Withdraw(val by: Person, override val at: Instant, val origin: Origin = Origin.NONE) : RequestCommand

    data class Supersede(val by: RequestId, override val at: Instant) : RequestCommand

    /** The expiry timer: the request ends only if it is still waiting and its time has come. */
    data class Expire(override val at: Instant) : RequestCommand

    /** The owning service applied the content whose hash is [hash]: refused unless it is the content approved. */
    data class MarkApplied(val hash: String, override val at: Instant, val by: Person? = null, val origin: Origin = Origin.NONE) :
        RequestCommand

    data class MarkApplyFailed(val reason: String, override val at: Instant, val by: Person? = null, val origin: Origin = Origin.NONE) :
        RequestCommand
}

/** Asked of a request that cannot take it; nothing is recorded. A vote turned away is recorded instead, as [RequestEvent.VoteRefused]. */
sealed interface Refusal {
    data object NotAsked : Refusal
    data object AskedDifferently : Refusal
    data object CommentRequired : Refusal
    data object NotOnTheRequest : Refusal
    data object NotWaiting : Refusal
    data object NotAgreed : Refusal
    data object NotYetDue : Refusal
    data object OnlyTheRequesterWithdraws : Refusal

    /** The owning service says it applied content other than what was approved: the record never says so. */
    data object NotWhatWasApproved : Refusal
}

fun decide(request: Request, command: RequestCommand): Either<Refusal, List<RequestEvent>> = when (command) {
    is RequestCommand.Ask -> ask(request, command)
    is RequestCommand.Approve -> approve(request, command)
    is RequestCommand.Reject -> reject(request, command)
    is RequestCommand.Comment -> comment(request, command)
    is RequestCommand.Withdraw -> withdraw(request, command)
    is RequestCommand.Supersede -> when (request) {
        is Request.Waiting -> listOf(RequestEvent.Superseded(command.by, command.at)).right()
        is Request.Unasked -> Refusal.NotAsked.left()
        is Request.Agreed, is Request.Ended -> Refusal.NotWaiting.left()
    }
    is RequestCommand.Expire -> when (request) {
        is Request.Waiting ->
            if (command.at.isBefore(request.asked.expiresAt)) Refusal.NotYetDue.left()
            else listOf(RequestEvent.Expired(command.at)).right()
        is Request.Unasked -> Refusal.NotAsked.left()
        // A timer that fires after the end finds nothing to do.
        is Request.Agreed, is Request.Ended -> emptyList<RequestEvent>().right()
    }
    is RequestCommand.MarkApplied -> when (request) {
        is Request.Agreed ->
            if (command.hash != request.asked.hash) Refusal.NotWhatWasApproved.left()
            else listOf(RequestEvent.Applied(command.at, command.by, command.hash, command.origin)).right()
        // At least once: the owning service may say so twice, of the same content.
        is Request.Ended -> when {
            request.how != Ending.Applied -> Refusal.NotAgreed.left()
            command.hash != request.asked.hash -> Refusal.NotWhatWasApproved.left()
            else -> emptyList<RequestEvent>().right()
        }
        is Request.Unasked -> Refusal.NotAsked.left()
        is Request.Waiting -> Refusal.NotAgreed.left()
    }
    is RequestCommand.MarkApplyFailed -> when (request) {
        is Request.Agreed -> listOf(RequestEvent.ApplyFailed(command.reason, command.at, command.by, command.origin)).right()
        is Request.Unasked -> Refusal.NotAsked.left()
        is Request.Waiting, is Request.Ended -> Refusal.NotAgreed.left()
    }
}

private fun ask(request: Request, command: RequestCommand.Ask): Either<Refusal, List<RequestEvent>> {
    if (request !is Request.Unasked) {
        // The same request asked again is a retry; the same id for different content is refused.
        val asked = asked(request)
        return if (asked.proposal == command.proposal && asked.requester == command.requester) emptyList<RequestEvent>().right()
        else Refusal.AskedDifferently.left()
    }
    val policy = command.policies.forKind(command.proposal.kind)
    val asked = Asked(
        command.id, command.proposal, command.requester, policy, command.policies.version,
        expiresAt = command.at.plus(policy.expiresAfter),
    )
    val requested = RequestEvent.Requested(asked, command.at, command.origin)
    val next = policy.autoApproval(command.proposal.facts)
        ?.let { RequestEvent.AutoApproved(it, command.at) }
        ?: RequestEvent.AwaitingApproval(policy.needed, policy.approvers, command.at)
    return listOf(requested, next).right()
}

private fun approve(request: Request, command: RequestCommand.Approve): Either<Refusal, List<RequestEvent>> {
    val waiting = when (request) {
        is Request.Unasked -> return Refusal.NotAsked.left()
        is Request.Agreed, is Request.Ended -> return refused(command.by, RefusedBecause.Ended, command.at, command.origin)
        is Request.Waiting -> request
    }
    val asked = waiting.asked
    ineligible(asked, command.by)?.let { return refused(command.by, it, command.at, command.origin) }
    if (waiting.approvedBy.any { it.subject == command.by.subject }) {
        return refused(command.by, RefusedBecause.AlreadyApproved, command.at, command.origin)
    }
    if (command.hash != asked.hash) return refused(command.by, RefusedBecause.NotWhatWasAsked, command.at, command.origin)

    val approved = RequestEvent.Approved(command.by, command.hash, command.comment, command.at, command.origin)
    val approvers = waiting.approvedBy.map { it.subject } + command.by.subject
    return if (approvers.size >= asked.policy.needed) listOf(approved, RequestEvent.ApprovalGiven(approvers, command.at)).right()
    else listOf(approved).right()
}

/** A rejection ends the request; one who approved may still reject, and the approval stays in the history. */
private fun reject(request: Request, command: RequestCommand.Reject): Either<Refusal, List<RequestEvent>> {
    if (command.comment.isBlank()) return Refusal.CommentRequired.left()
    val waiting = when (request) {
        is Request.Unasked -> return Refusal.NotAsked.left()
        is Request.Agreed, is Request.Ended -> return refused(command.by, RefusedBecause.Ended, command.at, command.origin)
        is Request.Waiting -> request
    }
    ineligible(waiting.asked, command.by)?.let { return refused(command.by, it, command.at, command.origin) }
    return listOf(RequestEvent.Rejected(command.by, command.comment, command.at, command.origin)).right()
}

private fun comment(request: Request, command: RequestCommand.Comment): Either<Refusal, List<RequestEvent>> {
    if (command.text.isBlank()) return Refusal.CommentRequired.left()
    val asked = when (request) {
        is Request.Unasked -> return Refusal.NotAsked.left()
        is Request.Ended -> return Refusal.NotWaiting.left()
        is Request.Waiting -> request.asked
        is Request.Agreed -> request.asked
    }
    val onIt = command.by.subject == asked.requester.subject || command.by.groups.any { it in asked.policy.approvers }
    return if (onIt) listOf(RequestEvent.Commented(command.by, command.text, command.at, command.origin)).right()
    else Refusal.NotOnTheRequest.left()
}

private fun withdraw(request: Request, command: RequestCommand.Withdraw): Either<Refusal, List<RequestEvent>> = when (request) {
    is Request.Waiting ->
        if (command.by.subject == request.asked.requester.subject) listOf(RequestEvent.Withdrawn(command.by, command.at, command.origin)).right()
        else Refusal.OnlyTheRequesterWithdraws.left()
    is Request.Unasked -> Refusal.NotAsked.left()
    // Nobody may withdraw a change once it is approved.
    is Request.Agreed, is Request.Ended -> Refusal.NotWaiting.left()
}

/** Why [person] may not vote on [asked], or null when they may. */
private fun ineligible(asked: Asked, person: Person): RefusedBecause? = when {
    person.subject == asked.requester.subject -> RefusedBecause.OwnRequest
    person.groups.none { it in asked.policy.approvers } -> RefusedBecause.NotAnApprover
    else -> null
}

private fun refused(by: Person, because: RefusedBecause, at: Instant, origin: Origin): Either<Refusal, List<RequestEvent>> =
    listOf(RequestEvent.VoteRefused(by, because, at, origin)).right()

private fun asked(request: Request): Asked = when (request) {
    is Request.Waiting -> request.asked
    is Request.Agreed -> request.asked
    is Request.Ended -> request.asked
    is Request.Unasked -> error("an unasked request has asked nothing")
}

fun evolve(request: Request, event: RequestEvent): Request = when (event) {
    is RequestEvent.Requested -> Request.Waiting(event.asked, emptyList())
    is RequestEvent.AwaitingApproval -> request
    is RequestEvent.AutoApproved -> Request.Agreed(asked(request), emptyList(), event.condition)
    is RequestEvent.Approved -> (request as Request.Waiting).copy(approvedBy = request.approvedBy + event.by)
    is RequestEvent.ApprovalGiven -> (request as Request.Waiting).let { Request.Agreed(it.asked, it.approvedBy, null) }
    is RequestEvent.Rejected -> ended(request, Ending.Rejected(event.by, event.comment))
    is RequestEvent.Withdrawn -> ended(request, Ending.Withdrawn(event.by))
    is RequestEvent.Superseded -> ended(request, Ending.Superseded(event.by))
    is RequestEvent.Expired -> ended(request, Ending.Expired)
    is RequestEvent.Applied -> ended(request, Ending.Applied)
    // Recorded, and change nothing about where the request stands.
    is RequestEvent.Commented, is RequestEvent.VoteRefused, is RequestEvent.ApplyFailed -> request
}

/** A request's state from its history, as a journal replays it. */
fun replay(events: List<RequestEvent>): Request = events.fold(Request.Unasked as Request, ::evolve)

private fun ended(request: Request, how: Ending): Request {
    val approvedBy = when (request) {
        is Request.Waiting -> request.approvedBy
        is Request.Agreed -> request.approvedBy
        is Request.Ended -> request.approvedBy
        is Request.Unasked -> emptyList()
    }
    return Request.Ended(asked(request), approvedBy, how)
}
