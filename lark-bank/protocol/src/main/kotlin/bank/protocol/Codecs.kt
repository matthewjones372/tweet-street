package bank.protocol

import arrow.core.Either
import bank.domain.Account
import bank.domain.AccountEvent
import bank.domain.TransferEvent
import bank.protocol.wire.GetBalance
import bank.protocol.wire.NodeSnapshot
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.StateCodec
import io.github.matthewjones372.lark.actor.versioned
import io.github.matthewjones372.lark.actor.versionedState
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.WireException
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.remote.delivery
import io.github.matthewjones372.lark.actor.remote.kotlinx.Kotlinx
import kotlinx.serialization.SerializationException
import bank.protocol.wire.Account as WireAccount
import bank.protocol.wire.AccountCommand as WireAccountCommand
import bank.protocol.wire.AccountError as WireAccountError
import bank.protocol.wire.AccountEvent as WireAccountEvent
import bank.protocol.wire.Balance as WireBalance
import bank.protocol.wire.BulkCredit as WireBulkCredit
import bank.protocol.wire.TransferError as WireTransferError
import bank.protocol.wire.TransferEvent as WireTransferEvent
import bank.protocol.wire.TransferRequest as WireTransferRequest
import bank.protocol.wire.TransferView as WireTransferView

// The tag tables (lark spec 0093): a tag is part of what is stored and sent, so it is never reused or renumbered.

private val accountEvents = Kotlinx.oneOf<WireAccountEvent> {
    name = "AccountEvent"
    message<WireAccountEvent.Opened>(1)
    message<WireAccountEvent.Deposited>(2)
    message<WireAccountEvent.Withdrawn>(3)
    message<WireAccountEvent.Debited>(4)
    message<WireAccountEvent.Credited>(5)
    message<WireAccountEvent.Refunded>(6)
    message<WireAccountEvent.LegsClosed>(7)
}

private val accountStates = Kotlinx.oneOf<WireAccount> {
    name = "AccountSnapshot"
    message<WireAccount.Unopened>(1)
    message<WireAccount.Open>(2)
}

private val accountRequests = Kotlinx.oneOf<Any> {
    name = "AccountRequest"
    message<WireAccountCommand.Open>(1)
    message<WireAccountCommand.Deposit>(2)
    message<WireAccountCommand.Withdraw>(3)
    message<WireAccountCommand.Debit>(4)
    message<WireAccountCommand.Credit>(5)
    message<WireAccountCommand.Refund>(6)
    message<GetBalance>(7)
    message<WireAccountCommand.Close>(8)
}

private val accountReplies = Kotlinx.oneOf<Any> {
    name = "AccountReply"
    message<WireBalance>(1)
    message<WireAccountError.NoSuchAccount>(2)
    message<WireAccountError.AlreadyOpen>(3)
    message<WireAccountError.InsufficientFunds>(4)
    message<WireAccountError.InvalidAmount>(5)
    message<WireAccountError.Unavailable>(6)
    message<WireAccountError.CurrencyMismatch>(7)
    message<WireAccountError.DailyLimitExceeded>(8)
}

private val bulkCredits = Kotlinx.oneOf<WireBulkCredit> {
    name = "BulkCreditSent"
    message<WireBulkCredit>(1)
}

private val transferEvents = Kotlinx.oneOf<WireTransferEvent> {
    name = "TransferEvent"
    message<WireTransferEvent.Requested>(1)
    message<WireTransferEvent.SourceDebited>(2)
    message<WireTransferEvent.Rejected>(3)
    message<WireTransferEvent.DestinationCredited>(4)
    message<WireTransferEvent.CreditRefused>(5)
    message<WireTransferEvent.SourceRefunded>(6)
    message<WireTransferEvent.Screened>(7)
}

private val transferRequests = Kotlinx.oneOf<WireTransferRequest> {
    name = "TransferRequest"
    message<WireTransferRequest.Start>(1)
    message<WireTransferRequest.Status>(2)
}

private val transferReplies = Kotlinx.oneOf<Any> {
    name = "TransferReply"
    message<WireTransferView>(1)
    message<WireTransferError.SameAccount>(2)
    message<WireTransferError.InvalidTransferAmount>(3)
    message<WireTransferError.NoSuchTransfer>(4)
    message<WireTransferError.TransferIdReused>(5)
    message<WireTransferError.Unavailable>(6)
}

private val nodeSnapshots = Kotlinx.oneOf<NodeSnapshot> {
    name = "NodeSnapshotOnTopic"
    message<NodeSnapshot>(1)
}

/** The .proto the wire shapes make, for readers in other languages: pinned as protocol/bank.proto. */
val bankProto: String
    get() = Kotlinx.proto(
        "bank.v1",
        accountEvents, accountStates, accountRequests, accountReplies, bulkCredits,
        transferEvents, transferRequests, transferReplies, nodeSnapshots,
    )

// ---- the journal ----

/** An account event's bytes as the wire shape writes them, unversioned, as bank.proto says. */
object AccountEventBody : EventCodec<AccountEvent> {
    override fun encode(event: AccountEvent): ByteArray = accountEvents.write(event.toWire())

    override fun decode(bytes: ByteArray): AccountEvent = accountEvents.read(bytes).toDomain()
}

private object AccountStateBody : StateCodec<Account> {
    override fun encode(state: Account): ByteArray = accountStates.write(state.toWire())

    override fun decode(bytes: ByteArray): Account = accountStates.read(bytes).toDomain()
}

private object TransferEventBody : EventCodec<TransferEvent> {
    override fun encode(event: TransferEvent): ByteArray = transferEvents.write(event.toWire())

    override fun decode(bytes: ByteArray): TransferEvent = transferEvents.read(bytes).toDomain()
}

/*
 * The journal's codecs are versioned (lark spec 0091): each event and snapshot is written marked with its version,
 * and one written before the bank versioned them, unmarked, is read as version 1. Changing a shape is version 2 with
 * an upgrade from 1, and older versions are never edited; docs/runbook.md says how to roll one out.
 */

/** Version of every event and snapshot the bank writes today. */
const val JOURNAL_VERSION = 1

val AccountEvents: EventCodec<AccountEvent> = versioned(JOURNAL_VERSION, AccountEventBody)

val AccountStates: StateCodec<Account> = versionedState(JOURNAL_VERSION, AccountStateBody)

val TransferEvents: EventCodec<TransferEvent> = versioned(JOURNAL_VERSION, TransferEventBody)

// ---- between nodes ----

internal object AccountAnswers : MessageCodec<AccountAnswer> {
    override fun write(message: AccountAnswer, out: WireOut) =
        out.bytes(accountReplies.write(message.fold({ it.toWire() }, { it.toWire() })))

    override fun read(input: WireIn): AccountAnswer = when (val read = wired { accountReplies.read(input.bytes()) }) {
        is WireBalance -> Either.Right(read.toDomain())
        is WireAccountError -> Either.Left(read.toDomain())
        else -> throw WireException("an account reply of no known kind: $read")
    }
}

internal object TransferAnswers : MessageCodec<TransferAnswer> {
    override fun write(message: TransferAnswer, out: WireOut) =
        out.bytes(transferReplies.write(message.fold({ it.toWire() }, { it.toWire() })))

    override fun read(input: WireIn): TransferAnswer = when (val read = wired { transferReplies.read(input.bytes()) }) {
        is WireTransferView -> Either.Right(read.toDomain())
        is WireTransferError -> Either.Left(read.toDomain())
        else -> throw WireException("a transfer reply of no known kind: $read")
    }
}

/** An account's protocol between nodes: an asked request, or a reliably delivered credit. */
object AccountMessages : MessageCodec<AccountMessage> {
    override fun write(message: AccountMessage, out: WireOut) = when (message) {
        is AccountAsk -> {
            out.int(1)
            out.reply(message.reply, AccountAnswers)
            out.bytes(accountRequests.write(message.command?.toWire() ?: GetBalance))
        }
        is BulkCreditSent -> {
            out.int(2)
            out.bytes(bulkCredits.write(message.credit.toWire()))
            out.delivery(message.delivery)
        }
    }

    override fun read(input: WireIn): AccountMessage = when (val tag = input.int()) {
        1 -> {
            val reply = input.reply(AccountAnswers)
            val request = wired { accountRequests.read(input.bytes()) }
            AccountAsk((request as? WireAccountCommand)?.toDomain(), reply)
        }
        2 -> BulkCreditSent(wired { bulkCredits.read(input.bytes()) }.toDomain(), input.delivery())
        else -> throw WireException("no account message has the tag $tag")
    }
}

/** A saga's protocol between nodes. Its answers to itself never leave the node, so they have no tag. */
object TransferMessages : MessageCodec<TransferMessage> {
    override fun write(message: TransferMessage, out: WireOut) = when (message) {
        is TransferAsk -> {
            out.int(1)
            out.reply(message.reply, TransferAnswers)
            out.bytes(transferRequests.write(message.request.toWire()))
        }
        Nudge -> out.int(2)
        is LegAnswered, is LegTimedOut, is ScreeningAnswered, ScreeningUnanswered -> error("$message is the saga's message to itself and never crosses a node")
    }

    override fun read(input: WireIn): TransferMessage = when (val tag = input.int()) {
        1 -> {
            val reply = input.reply(TransferAnswers)
            TransferAsk(wired { transferRequests.read(input.bytes()) }.toDomain(), reply)
        }
        2 -> Nudge
        else -> throw WireException("no transfer message has the tag $tag")
    }
}

/** A node's snapshot on the `ops` topic (bank spec 0007). */
object NodeSnapshots : MessageCodec<NodeSnapshot> {
    override fun write(message: NodeSnapshot, out: WireOut) = out.bytes(nodeSnapshots.write(message))

    override fun read(input: WireIn): NodeSnapshot = wired { nodeSnapshots.read(input.bytes()) }
}

/** Bytes another node sent that do not read are the wire's fault, not a bug here. */
private inline fun <A> wired(read: () -> A): A = try {
    read()
} catch (broken: SerializationException) {
    throw WireException("not a message the bank can read: ${broken.message}").apply { initCause(broken) }
}

/** The journal's kinds: the first half of every persistence id, and what a projection follows. */
object Kinds {
    const val ACCOUNT = "account"
    const val TRANSFER = "transfer"
}
