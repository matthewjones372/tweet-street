package bank.approvals.app

import bank.approvals.domain.RequestId
import bank.approvals.protocol.Claim
import bank.approvals.protocol.Kinds
import bank.approvals.protocol.Release
import bank.approvals.protocol.SubjectChange
import bank.approvals.protocol.SubjectEvents
import bank.approvals.protocol.SubjectMessage
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Remembered
import io.github.matthewjones372.lark.actor.persistent

/** A subject's live request, if it has one: the only request for it anyone is voting on. */
data class Live(val request: RequestId?) {
    fun evolve(change: SubjectChange) = when (change) {
        is SubjectChange.Claimed -> Live(change.request)
        is SubjectChange.Released -> if (request == change.request) Live(null) else this
    }
}

/**
 * One subject (`checks/rule/large-transfer`): the one entity that knows which request for it is live, so a newer one
 * is told what it replaces, however many nodes it was asked on.
 */
fun subject(subject: String): Behaviour<SubjectMessage, Remembered<Live>, JournalConflict> =
    persistent<SubjectMessage, SubjectChange, Live>(
        id = PersistenceId(Kinds.SUBJECT, subject),
        empty = Live(null),
        codec = SubjectEvents,
        command = { _, state, message ->
            when (message) {
                is Claim ->
                    if (state.request == message.request) none().then { _ -> message.reply("") }
                    else persist(SubjectChange.Claimed(message.request)).then { _ -> message.reply(state.request?.value.orEmpty()) }
                is Release ->
                    if (state.request == message.request) persist(SubjectChange.Released(message.request)) else none()
            }
        },
        event = Live::evolve,
    )
