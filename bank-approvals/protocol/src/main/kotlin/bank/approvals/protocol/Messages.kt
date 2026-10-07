package bank.approvals.protocol

import arrow.core.Either
import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import bank.approvals.domain.Policies
import bank.approvals.domain.Policy
import bank.approvals.domain.Proposal
import bank.approvals.domain.Refusal
import bank.approvals.domain.RequestCommand
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import io.github.matthewjones372.lark.actor.Reply
import java.time.Instant

/** What a request answers: its whole history, which the asker replays, or why it could not take what it was asked. */
typealias RequestAnswer = Either<Refusal, List<RequestEvent>>

/** What a request is asked to do. The time is the entity's own, stamped when it decides. */
sealed interface Act {
    /** The policy is resolved where the request is made, so every node decides it under the version it was asked under. */
    data class Ask(val proposal: Proposal, val requester: Person, val policy: Policy, val policyVersion: String, val origin: Origin) : Act
    data class Approve(val by: Person, val hash: String, val comment: String?, val origin: Origin) : Act
    data class Reject(val by: Person, val comment: String, val origin: Origin) : Act
    data class Comment(val by: Person, val text: String, val origin: Origin) : Act
    data class Withdraw(val by: Person, val origin: Origin) : Act
    data class Supersede(val by: RequestId) : Act

    /** The owning service [by] applied the content whose hash is [hash]. */
    data class MarkApplied(val by: Person, val hash: String, val origin: Origin) : Act
    data class MarkApplyFailed(val by: Person, val reason: String, val origin: Origin) : Act
    data object Read : Act
}

/** The domain's command for [act], at [at]; null for a read, which changes nothing. */
fun Act.command(id: RequestId, at: Instant): RequestCommand? = when (this) {
    is Act.Ask -> RequestCommand.Ask(id, proposal, requester, Policies(policyVersion, mapOf(proposal.kind to policy)), at, origin)
    is Act.Approve -> RequestCommand.Approve(by, hash, comment, at, origin)
    is Act.Reject -> RequestCommand.Reject(by, comment, at, origin)
    is Act.Comment -> RequestCommand.Comment(by, text, at, origin)
    is Act.Withdraw -> RequestCommand.Withdraw(by, at, origin)
    is Act.Supersede -> RequestCommand.Supersede(by, at)
    is Act.MarkApplied -> RequestCommand.MarkApplied(hash, at, by, origin)
    is Act.MarkApplyFailed -> RequestCommand.MarkApplyFailed(reason, at, by, origin)
    Act.Read -> null
}

/** What a request entity is sent. */
sealed interface RequestMessage

data class RequestAsk(val act: Act, val reply: Reply<RequestAnswer>) : RequestMessage

/** Look at where the request stands and take the next step: sent on start, and by whoever wants it moving. */
data object Wake : RequestMessage

/** The request's time is up, if it is still waiting. Only ever sent by the request to itself. */
data object ExpiryDue : RequestMessage

/** Time to ask the owning service to apply it again. Only ever sent by the request to itself. */
data object AskOwnerAgain : RequestMessage

/** What a subject's entity is sent: it knows the one live request for its subject. */
sealed interface SubjectMessage

/** [request] is now the subject's live request; the answer is the one it replaces, or "" when there was none. */
data class Claim(val request: RequestId, val reply: Reply<String>) : SubjectMessage

/** [request] has ended, and is the subject's live request no longer if it was. */
data class Release(val request: RequestId) : SubjectMessage

/** The journal's kinds: the first half of every persistence id. */
object Kinds {
    const val REQUEST = "request"
    const val SUBJECT = "subject"
}
