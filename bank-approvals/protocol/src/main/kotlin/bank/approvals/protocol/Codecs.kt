package bank.approvals.protocol

import arrow.core.Either
import bank.approvals.domain.Chain
import bank.approvals.domain.Refusal
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.approvals.protocol.wire.ByteArrayEvent
import bank.approvals.protocol.wire.History
import bank.approvals.protocol.wire.Linked
import bank.approvals.protocol.wire.Refused
import bank.approvals.protocol.wire.SubjectEvent
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.WireException
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.remote.kotlinx.Kotlinx
import io.github.matthewjones372.lark.actor.versioned
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import bank.approvals.protocol.wire.Act as WireAct
import bank.approvals.protocol.wire.RequestEvent as WireEvent

// The tag tables (lark spec 0093): a tag is part of what is stored and sent, so it is never reused or renumbered.

private val requestEvents = Kotlinx.oneOf<WireEvent> {
    name = "RequestEvent"
    message<WireEvent.Requested>(1)
    message<WireEvent.AutoApproved>(2)
    message<WireEvent.AwaitingApproval>(3)
    message<WireEvent.Approved>(4)
    message<WireEvent.ApprovalGiven>(5)
    message<WireEvent.Rejected>(6)
    message<WireEvent.Commented>(7)
    message<WireEvent.VoteRefused>(8)
    message<WireEvent.Withdrawn>(9)
    message<WireEvent.Superseded>(10)
    message<WireEvent.Expired>(11)
    message<WireEvent.Applied>(12)
    message<WireEvent.ApplyFailed>(13)
}

private val acts = Kotlinx.oneOf<WireAct> {
    name = "Act"
    message<WireAct.Ask>(1)
    message<WireAct.Approve>(2)
    message<WireAct.Reject>(3)
    message<WireAct.Comment>(4)
    message<WireAct.Withdraw>(5)
    message<WireAct.Supersede>(6)
    message<WireAct.MarkApplied>(7)
    message<WireAct.MarkApplyFailed>(8)
    message<WireAct.Read>(9)
}

private val answers = Kotlinx.oneOf<Any> {
    name = "RequestAnswer"
    message<History>(1)
    message<Refused>(2)
}

private val subjectEvents = Kotlinx.oneOf<SubjectEvent> {
    name = "SubjectEvent"
    message<SubjectEvent.Claimed>(1)
    message<SubjectEvent.Released>(2)
}

/** The .proto the wire shapes make, for readers in other languages. */
val approvalsProto: String get() = Kotlinx.proto("approvals.v1", requestEvents, acts, answers, subjectEvents)

// ---- the journal ----

/** Version of every event written today (lark spec 0091): a changed shape is version 2 with an upgrade from 1. */
const val JOURNAL_VERSION = 1

/** One event's bytes as the history's chain hashes them: the wire shape, unversioned. */
fun RequestEvent.stored(): ByteArray = requestEvents.write(toWire())

/** A request event as the journal keeps it: the event, and its link in the request's chain (lark-bank spec 0019). */
data class ChainedEvent(val event: RequestEvent, val link: String)

/** A stored event's bytes and link as written, before anything reads them: what the chain is checked against. */
class StoredLink(val event: ByteArray, val link: String)

private object LinkedBody : EventCodec<StoredLink> {
    override fun encode(event: StoredLink): ByteArray = ProtoBuf.encodeToByteArray(Linked.serializer(), Linked(event.event, event.link))

    override fun decode(bytes: ByteArray): StoredLink = ProtoBuf.decodeFromByteArray(Linked.serializer(), bytes).let { StoredLink(it.event, it.link) }
}

/** The journal's entries as they are stored: each event's bytes and link, not yet read as an event. */
val StoredLinks: EventCodec<StoredLink> = versioned(JOURNAL_VERSION, LinkedBody)

private object ChainedBody : EventCodec<ChainedEvent> {
    override fun encode(event: ChainedEvent): ByteArray = LinkedBody.encode(StoredLink(event.event.stored(), event.link))

    override fun decode(bytes: ByteArray): ChainedEvent =
        LinkedBody.decode(bytes).let { ChainedEvent(requestEvents.read(it.event).toDomain(), it.link) }
}

val RequestEvents: EventCodec<ChainedEvent> = versioned(JOURNAL_VERSION, ChainedBody)

/** [events], linked after [head] in the order given: what a request writes to its journal. */
fun chained(head: String?, events: List<RequestEvent>): List<ChainedEvent> {
    var previous = head
    return events.map { event ->
        val link = Chain.link(previous, event.stored().toHexString())
        previous = link
        ChainedEvent(event, link)
    }
}

/** A subject's claims, as its journal holds them: the request ids alone. */
sealed interface SubjectChange {
    val request: RequestId

    data class Claimed(override val request: RequestId) : SubjectChange
    data class Released(override val request: RequestId) : SubjectChange
}

private object SubjectEventBody : EventCodec<SubjectChange> {
    override fun encode(event: SubjectChange): ByteArray = subjectEvents.write(
        when (event) {
            is SubjectChange.Claimed -> SubjectEvent.Claimed(event.request.value)
            is SubjectChange.Released -> SubjectEvent.Released(event.request.value)
        },
    )

    override fun decode(bytes: ByteArray): SubjectChange = when (val read = subjectEvents.read(bytes)) {
        is SubjectEvent.Claimed -> SubjectChange.Claimed(RequestId(read.request))
        is SubjectEvent.Released -> SubjectChange.Released(RequestId(read.request))
    }
}

val SubjectEvents: EventCodec<SubjectChange> = versioned(JOURNAL_VERSION, SubjectEventBody)

// ---- between nodes ----

/** Every refusal by name, both ways: a name is part of the wire, so a renamed refusal is a new one. */
internal val refusals: Map<String, Refusal> = listOf(
    Refusal.NotAsked, Refusal.AskedDifferently, Refusal.CommentRequired, Refusal.NotOnTheRequest, Refusal.NotWaiting,
    Refusal.NotAgreed, Refusal.NotYetDue, Refusal.OnlyTheRequesterWithdraws, Refusal.NotWhatWasApproved,
).associateBy { it::class.simpleName!! }

internal object RequestAnswers : MessageCodec<RequestAnswer> {
    override fun write(message: RequestAnswer, out: WireOut) = out.bytes(
        answers.write(
            message.fold(
                { refusal -> Refused(refusal::class.simpleName!!) },
                { events -> History(events.map { ByteArrayEvent(it.stored()) }) },
            ),
        ),
    )

    override fun read(input: WireIn): RequestAnswer = when (val read = wired { answers.read(input.bytes()) }) {
        is History -> Either.Right(read.events.map { requestEvents.read(it.bytes).toDomain() })
        is Refused -> Either.Left(refusals[read.refusal] ?: throw WireException("no refusal is called ${read.refusal}"))
        else -> throw WireException("a request answer of no known kind: $read")
    }
}

private fun Act.toWire(): WireAct = when (this) {
    is Act.Ask -> WireAct.Ask(proposal.toWire(), requester.toWire(), policy.toWire(), policyVersion, origin.toWire())
    is Act.Approve -> WireAct.Approve(by.toWire(), hash, comment.orEmpty(), origin.toWire())
    is Act.Reject -> WireAct.Reject(by.toWire(), comment, origin.toWire())
    is Act.Comment -> WireAct.Comment(by.toWire(), text, origin.toWire())
    is Act.Withdraw -> WireAct.Withdraw(by.toWire(), origin.toWire())
    is Act.Supersede -> WireAct.Supersede(by.value)
    is Act.MarkApplied -> WireAct.MarkApplied(by.toWire(), hash, origin.toWire())
    is Act.MarkApplyFailed -> WireAct.MarkApplyFailed(reason, by.toWire(), origin.toWire())
    Act.Read -> WireAct.Read
}

private fun WireAct.toDomain(): Act = when (this) {
    is WireAct.Ask -> Act.Ask(proposal.toDomain(), requester.toDomain(), policy.toDomain(), policyVersion, origin.toDomain())
    is WireAct.Approve -> Act.Approve(by.toDomain(), hash, comment.ifEmpty { null }, origin.toDomain())
    is WireAct.Reject -> Act.Reject(by.toDomain(), comment, origin.toDomain())
    is WireAct.Comment -> Act.Comment(by.toDomain(), text, origin.toDomain())
    is WireAct.Withdraw -> Act.Withdraw(by.toDomain(), origin.toDomain())
    is WireAct.Supersede -> Act.Supersede(RequestId(by))
    is WireAct.MarkApplied -> Act.MarkApplied(by.toDomain(), hash, origin.toDomain())
    is WireAct.MarkApplyFailed -> Act.MarkApplyFailed(by.toDomain(), reason, origin.toDomain())
    WireAct.Read -> Act.Read
}

/** A request's protocol between nodes. What it sends itself never leaves its node, so it has no tag. */
object RequestMessages : MessageCodec<RequestMessage> {
    override fun write(message: RequestMessage, out: WireOut) = when (message) {
        is RequestAsk -> {
            out.int(1)
            out.reply(message.reply, RequestAnswers)
            out.bytes(acts.write(message.act.toWire()))
        }
        Wake -> out.int(2)
        ExpiryDue, AskOwnerAgain -> error("$message is the request's message to itself and never crosses a node")
    }

    override fun read(input: WireIn): RequestMessage = when (val tag = input.int()) {
        1 -> {
            val reply = input.reply(RequestAnswers)
            RequestAsk(wired { acts.read(input.bytes()) }.toDomain(), reply)
        }
        2 -> Wake
        else -> throw WireException("no request message has the tag $tag")
    }
}

object SubjectMessages : MessageCodec<SubjectMessage> {
    override fun write(message: SubjectMessage, out: WireOut) = when (message) {
        is Claim -> {
            out.int(1)
            out.reply(message.reply, Codecs.string)
            Codecs.string.write(message.request.value, out)
        }
        is Release -> {
            out.int(2)
            Codecs.string.write(message.request.value, out)
        }
    }

    override fun read(input: WireIn): SubjectMessage = when (val tag = input.int()) {
        1 -> {
            val reply = input.reply(Codecs.string)
            Claim(RequestId(Codecs.string.read(input)), reply)
        }
        2 -> Release(RequestId(Codecs.string.read(input)))
        else -> throw WireException("no subject message has the tag $tag")
    }
}

/** Bytes another node sent that do not read are the wire's fault, not a bug here. */
private inline fun <A> wired(read: () -> A): A = try {
    read()
} catch (broken: SerializationException) {
    throw WireException("not a message approvals can read: ${broken.message}").apply { initCause(broken) }
}
