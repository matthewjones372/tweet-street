package bank.protocol

import arrow.core.Either
import bank.domain.Account
import bank.domain.AccountCommand
import bank.domain.AccountError
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Balance
import bank.domain.Money
import bank.domain.TransferEvent
import bank.domain.TransferError
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.domain.TransferView
import bank.domain.Unavailable
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Delivery
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Refs
import io.github.matthewjones372.lark.actor.remote.decode
import io.github.matthewjones372.lark.actor.remote.encode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RoundTripSpec {
    private val t = TransferId("t-1")

    @Test
    fun `every account event survives the journal`() {
        val events = listOf(
            AccountEvent.Opened("ada", gbp(100), "open-1", 1),
            AccountEvent.Deposited(gbp(5), "d", 2),
            AccountEvent.Withdrawn(gbp(3), "w", 3),
            AccountEvent.Debited(t, gbp(7), 4),
            AccountEvent.Credited(t, gbp(7), 5),
            AccountEvent.Refunded(t, gbp(7), 6),
            AccountEvent.LegsClosed(t, 7),
        )
        events.map { AccountEvents.decode(AccountEvents.encode(it)) } shouldBe events
    }

    @Test
    fun `every transfer event survives the journal`() {
        val events = listOf(
            TransferEvent.Requested(AccountId("a"), AccountId("b"), gbp(9), 1),
            TransferEvent.SourceDebited(2),
            TransferEvent.Rejected("no", 3),
            TransferEvent.DestinationCredited(4),
            TransferEvent.CreditRefused("closed", 5),
            TransferEvent.SourceRefunded(6),
        )
        events.map { TransferEvents.decode(TransferEvents.encode(it)) } shouldBe events
    }

    @Test
    fun `an account's snapshot is the account`() {
        val open = Account.Open("ada", gbp(42), listOf("a", "b"), setOf("debit:t-1"))
        AccountStates.decode(AccountStates.encode(open)) shouldBe open
        AccountStates.decode(AccountStates.encode(Account.Unopened)) shouldBe Account.Unopened
    }

    @Test
    fun `every command crosses between nodes as itself, and no command is a balance enquiry`() {
        val commands = listOf(
            AccountCommand.Open("ada", gbp(1), "r"),
            AccountCommand.Deposit(gbp(2), "r"),
            AccountCommand.Withdraw(gbp(3), "r"),
            AccountCommand.Debit(t, gbp(4)),
            AccountCommand.Credit(t, gbp(5)),
            AccountCommand.Refund(t, gbp(6)),
            AccountCommand.Close(t),
            null,
        )
        commands.map { (AccountMessages.roundTrip(AccountAsk(it, Ignored())) as AccountAsk).command } shouldBe commands
    }

    @Test
    fun `a bulk credit and a transfer's requests cross between nodes as themselves`() {
        val delivery = Delivery("payroll", "acc-1", 3, Delivery.NoOne)
        val bulk = AccountMessages.roundTrip(BulkCreditSent(BulkCredit(gbp(250), "payroll:p:acc-1"), delivery))
        (bulk as BulkCreditSent).credit shouldBe BulkCredit(gbp(250), "payroll:p:acc-1")

        val requests = listOf(TransferRequest.Start(AccountId("a"), AccountId("b"), gbp(9)), TransferRequest.Status)
        requests.map { (TransferMessages.roundTrip(TransferAsk(it, Ignored())) as TransferAsk).request } shouldBe requests
        TransferMessages.roundTrip(Nudge) shouldBe Nudge
    }

    @Test
    fun `every answer an account or a transfer gives crosses the wire as itself`() {
        val accountAnswers: List<AccountAnswer> = listOf(
            Either.Right(Balance("a", "ada", gbp(42))),
            Either.Left(AccountError.NoSuchAccount("a")),
            Either.Left(AccountError.AlreadyOpen("a")),
            Either.Left(AccountError.InsufficientFunds("a", gbp(1), gbp(2))),
            Either.Left(AccountError.InvalidAmount(gbp(-1))),
            Either.Left(AccountError.CurrencyMismatch("a", "GBP", "BTC")),
            Either.Left(Unavailable("slow")),
        )
        accountAnswers.map { AccountAnswers.roundTrip(it) } shouldBe accountAnswers

        val transferAnswers: List<TransferAnswer> = listOf(
            Either.Right(TransferView("t-1", "a", "b", gbp(9), TransferStatus.Refunding, "closed")),
            Either.Right(TransferView("t-2", "a", "b", Money.ofMinor(9, BTC), TransferStatus.Completed, null)),
            Either.Left(TransferError.SameAccount("a")),
            Either.Left(TransferError.InvalidTransferAmount(Money.ofMinor(0, BTC))),
            Either.Left(TransferError.NoSuchTransfer("t-3")),
            Either.Left(TransferError.TransferIdReused("t-4")),
            Either.Left(Unavailable("slow")),
        )
        transferAnswers.map { TransferAnswers.roundTrip(it) } shouldBe transferAnswers
    }
}

/** A reply that is never answered: what a round trip carries across and hands back. */
private class Ignored<A : Any> : Reply<A> {
    override val address = Address("here", "/temp/ignored", 0)

    override fun invoke(answer: A) = Unit
}

/** Refs that hand back the reply they were given, so a message's reply crosses a round trip unchanged. */
private class Kept : Refs {
    private val replies = mutableMapOf<Address, Reply<*>>()

    override fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>) = ref.address

    override fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>): Address =
        Address("here", "/temp/reply-${replies.size}", 0).also { replies[it] = reply }

    override fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M> = object : ActorRef<M> {
        override val address = address

        override fun tell(message: M) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any> reply(address: Address, answers: MessageCodec<A>) = replies.getValue(address) as Reply<A>
}

internal fun <M : Any> MessageCodec<M>.roundTrip(message: M): M = Kept().let { refs -> decode(encode(message, refs), refs) }
