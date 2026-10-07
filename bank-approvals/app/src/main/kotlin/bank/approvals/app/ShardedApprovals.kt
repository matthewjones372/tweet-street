package bank.approvals.app

import arrow.core.Either
import arrow.core.left
import bank.approvals.api.Approvals
import bank.approvals.api.Unanswered
import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import bank.approvals.domain.Policies
import bank.approvals.domain.Proposal
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.approvals.protocol.Act
import bank.approvals.protocol.Claim
import bank.approvals.protocol.RequestAnswer
import bank.approvals.protocol.RequestAsk
import bank.approvals.protocol.RequestMessage
import bank.approvals.protocol.SubjectMessage
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.AskFailure
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.logWarn
import java.util.UUID
import kotlin.time.Duration

/**
 * The requests as sharded entities: each call an ask to the one entity that owns the id, on whichever node that is.
 * An ask not answered in time is unavailable, and asking again is safe: a request decides each act once.
 */
class ShardedApprovals(
    private val requests: (String) -> ActorRef<RequestMessage>,
    private val subjects: (String) -> ActorRef<SubjectMessage>,
    private val policies: () -> Policies,
    private val askTimeout: Duration,
    private val newId: () -> String = { "req-${UUID.randomUUID()}" },
) : Approvals {

    override fun ask(proposal: Proposal, requester: Person, origin: Origin): Either<Unanswered, Pair<RequestId, List<RequestEvent>>> {
        val id = RequestId(newId())
        val current = policies()
        return act(id, Act.Ask(proposal, requester, current.forKind(proposal.kind), current.version, origin))
            .map { history -> id to history }
            .onRight { supersede(id, proposal.subject) }
    }

    /**
     * The subject's live request is now [id], and the one it replaces is superseded. Should this node stop between the
     * two, the older one stays live until it ends another way; the newer one is the one people are shown.
     */
    private fun supersede(id: RequestId, subject: String) {
        subjects(subject).ask<SubjectMessage, String>(askTimeout) { Claim(id, it) }.fold(
            { failure -> logWarn("request ${id.value} did not claim $subject: ${failure.said()}") },
            { previous ->
                if (previous.isNotEmpty() && previous != id.value) {
                    act(RequestId(previous), Act.Supersede(id)).onLeft {
                        logWarn("request $previous was not superseded by ${id.value}: $it")
                    }
                }
            },
        )
    }

    override fun act(id: RequestId, act: Act): Either<Unanswered, List<RequestEvent>> =
        requests(id.value).ask<RequestMessage, RequestAnswer>(askTimeout) { RequestAsk(act, it) }
            .fold(
                { failure -> Unanswered.Unavailable("request ${id.value} ${failure.said()}").left() },
                { answer -> answer.mapLeft { Unanswered.Refused(it) } },
            )
}

private fun AskFailure.said() = when (this) {
    AskFailure.TimedOut -> "did not answer in time"
    AskFailure.Stopped -> "stopped before answering"
    AskFailure.Unreachable -> "is on a node that cannot be reached"
}
