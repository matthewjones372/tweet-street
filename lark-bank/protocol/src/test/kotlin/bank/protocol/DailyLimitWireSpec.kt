package bank.protocol

import arrow.core.Either
import bank.domain.Account
import bank.domain.AccountCommand
import bank.domain.AccountError
import bank.domain.AccountEvent
import bank.v1.Bank
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Reply
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Bank spec 0027: an account's daily limit in the journal, its snapshot and between nodes; older rows still read. */
class DailyLimitWireSpec {

    @Test
    fun `an Opened carries the limit the account opened with`() {
        val opened = AccountEvent.Opened("ada", gbp(100), "open-1", 1, dailyLimit = gbp(50_000))
        AccountEvents.decode(AccountEvents.encode(opened)) shouldBe opened
    }

    @Test
    fun `an Opened written before limits reads with none, which the account takes as the default`() {
        val old = Bank.AccountEvent.newBuilder()
            .setOpened(
                Bank.Opened.newBuilder().setOwner("ada").setReference("open-1").setAtMillis(1)
                    .setInitial(wireGbp("1.00")),
            )
            .build()
            .toByteArray()
        AccountEvents.decode(old) shouldBe AccountEvent.Opened("ada", gbp(100), "open-1", 1, dailyLimit = null)
    }

    @Test
    fun `an account's snapshot keeps its limit and what it has paid out today`() {
        val open = Account.Open(
            "ada", gbp(42), listOf("a"), setOf("debit:t-1"),
            dailyLimit = gbp(500), paidOutOn = 20_736, paidOut = gbp(300), debitedOn = setOf("t-1"),
        )
        AccountStates.decode(AccountStates.encode(open)) shouldBe open
    }

    @Test
    fun `a snapshot taken before limits reads as an account that has paid nothing out`() {
        val old = Bank.AccountSnapshot.newBuilder()
            .setOpenAccount(Bank.OpenAccount.newBuilder().setOwner("ada").setBalance(wireGbp("0.42")).addRecent("a"))
            .build()
            .toByteArray()
        AccountStates.decode(old) shouldBe Account.Open("ada", gbp(42), listOf("a"))
    }

    @Test
    fun `opening with a limit, and the refusal for passing it, cross between nodes as themselves`() {
        val open = AccountCommand.Open("ada", gbp(1), "r", dailyLimit = gbp(500))
        (AccountMessages.roundTrip(AccountAsk(open, Unanswered())) as AccountAsk).command shouldBe open

        val refused: AccountAnswer =
            Either.Left(AccountError.DailyLimitExceeded("a", gbp(1_000_000), gbp(250), gbp(300)))
        AccountAnswers.roundTrip(refused) shouldBe refused
    }

    private fun wireGbp(amount: String) =
        Bank.Money.newBuilder().setAmount(amount).setCurrency(Bank.Currency.newBuilder().setCode("GBP").setExponent(2))
}

private class Unanswered<A : Any> : Reply<A> {
    override val address = Address("here", "/temp/unanswered", 0)

    override fun invoke(answer: A) = Unit
}
