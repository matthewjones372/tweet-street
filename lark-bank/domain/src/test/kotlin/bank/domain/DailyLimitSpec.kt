package bank.domain

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Bank spec 0027: what an account pays out in a UTC day is capped. */
class DailyLimitSpec {
    private val id = AccountId("acc-1")
    private val day = 86_400_000L

    /** 2026-10-10T00:00Z, and noon of it. */
    private val today = 20_736 * day
    private val noon = today + 12 * 3_600_000L

    private fun Account.after(command: AccountCommand, now: Long = noon): Account =
        decide(id, command, now).shouldBeRight().fold(this, Account::evolve)

    private fun Account.after(vararg commands: AccountCommand): Account =
        commands.fold(this) { state, command -> state.after(command) }

    private fun yen(units: Long) = Money.ofMinor(units, JPY)

    private val rich = Account.Unopened.after(AccountCommand.Open("ada", gbp(5_000_000), "open"))

    @Test
    fun `an account opened without a limit may pay out 10,000 in its currency a day`() {
        val paid = rich.after(AccountCommand.Withdraw(gbp(999_999), "w-1"))

        paid.decide(id, AccountCommand.Withdraw(gbp(1), "w-2"), noon).shouldBeRight() shouldHaveSize 1
        paid.after(AccountCommand.Withdraw(gbp(1), "w-2"))
            .decide(id, AccountCommand.Withdraw(gbp(1), "w-3"), noon).shouldBeLeft() shouldBe
            AccountError.DailyLimitExceeded("acc-1", gbp(1_000_000), gbp(0), gbp(1))
    }

    @Test
    fun `the default is 10,000 at the currency's own places`() {
        val yen = Account.Unopened.after(AccountCommand.Open("ada", Money.ofMinor(50_000, JPY), "open"))

        yen.decide(id, AccountCommand.Withdraw(Money.ofMinor(10_001, JPY), "w"), noon).shouldBeLeft() shouldBe
            AccountError.DailyLimitExceeded("acc-1", yen(10_000), yen(10_000), yen(10_001))
    }

    @Test
    fun `an account opened with a limit keeps that one`() {
        val capped = Account.Unopened.after(AccountCommand.Open("ada", gbp(100_000), "open", dailyLimit = gbp(50_000)))

        capped.decide(id, AccountCommand.Withdraw(gbp(50_001), "w"), noon).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.DailyLimitExceeded>().limit shouldBe gbp(50_000)
        capped.decide(id, AccountCommand.Withdraw(gbp(50_000), "w"), noon).shouldBeRight() shouldHaveSize 1
    }

    @Test
    fun `the limit an account opens with is written in its Opened, the default included`() {
        val opened = Account.Unopened.decide(id, AccountCommand.Open("ada", gbp(1), "open"), noon)
            .shouldBeRight().single()
        (opened as AccountEvent.Opened).dailyLimit shouldBe gbp(1_000_000)
    }

    @Test
    fun `an Opened written before limits reads as the default`() {
        val old = Account.Unopened.evolve(AccountEvent.Opened("ada", gbp(5_000_000), "open", 0))

        old.decide(id, AccountCommand.Withdraw(gbp(1_000_001), "w"), noon).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.DailyLimitExceeded>().limit shouldBe gbp(1_000_000)
    }

    @Test
    fun `a limit in another currency, or below zero, is refused at opening`() {
        Account.Unopened.decide(id, AccountCommand.Open("ada", gbp(1), "open", dailyLimit = yen(5)), noon)
            .shouldBeLeft() shouldBe AccountError.CurrencyMismatch("acc-1", "GBP", "JPY")
        Account.Unopened.decide(id, AccountCommand.Open("ada", gbp(1), "open", dailyLimit = gbp(-1)), noon)
            .shouldBeLeft() shouldBe AccountError.InvalidAmount(gbp(-1))
    }

    @Test
    fun `a limit of zero pays nothing out, and still takes money in`() {
        val locked = Account.Unopened.after(AccountCommand.Open("ada", gbp(100), "open", dailyLimit = gbp(0)))

        locked.decide(id, AccountCommand.Withdraw(gbp(1), "w"), noon).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.DailyLimitExceeded>()
        locked.decide(id, AccountCommand.Deposit(gbp(1), "d"), noon).shouldBeRight() shouldHaveSize 1
        locked.decide(id, AccountCommand.Credit(TransferId("t"), gbp(1)), noon).shouldBeRight() shouldHaveSize 1
    }

    @Test
    fun `withdrawals and debits count together against the one limit`() {
        val paid = rich.after(
            AccountCommand.Withdraw(gbp(600_000), "w-1"),
            AccountCommand.Debit(TransferId("t-1"), gbp(300_000)),
        )

        paid.decide(id, AccountCommand.Debit(TransferId("t-2"), gbp(100_001)), noon).shouldBeLeft() shouldBe
            AccountError.DailyLimitExceeded("acc-1", gbp(1_000_000), gbp(100_000), gbp(100_001))
        paid.decide(id, AccountCommand.Withdraw(gbp(100_000), "w-2"), noon).shouldBeRight() shouldHaveSize 1
    }

    @Test
    fun `the refusal says the limit and what is left today`() {
        val paid = rich.after(AccountCommand.Withdraw(gbp(975_000), "w-1"))

        paid.decide(id, AccountCommand.Withdraw(gbp(30_000), "w-2"), noon).shouldBeLeft().message shouldBe
            "Account acc-1 may pay out 10000.00 GBP a day and has 250.00 GBP of that left today, not 300.00 GBP"
    }

    @Test
    fun `a new UTC day starts again from nothing, and the last millisecond of the old one does not`() {
        val paid = rich.after(AccountCommand.Withdraw(gbp(1_000_000), "w-1"))

        paid.decide(id, AccountCommand.Withdraw(gbp(1), "w-2"), today + day - 1).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.DailyLimitExceeded>()
        paid.decide(id, AccountCommand.Withdraw(gbp(1_000_000), "w-2"), today + day).shouldBeRight() shouldHaveSize 1
        paid.after(AccountCommand.Withdraw(gbp(400_000), "w-2"), now = today + day)
            .decide(id, AccountCommand.Withdraw(gbp(600_001), "w-3"), today + day).shouldBeLeft() shouldBe
            AccountError.DailyLimitExceeded("acc-1", gbp(1_000_000), gbp(600_000), gbp(600_001))
    }

    @Test
    fun `a refunded debit no longer counts against today's limit`() {
        val refunded = rich.after(
            AccountCommand.Debit(TransferId("t-1"), gbp(800_000)),
            AccountCommand.Refund(TransferId("t-1"), gbp(800_000)),
        )

        refunded.decide(id, AccountCommand.Withdraw(gbp(1_000_000), "w"), noon).shouldBeRight() shouldHaveSize 1
    }

    @Test
    fun `a refund never counts as a payout, and is never refused for the limit`() {
        val spent = rich.after(
            AccountCommand.Debit(TransferId("t-1"), gbp(400_000)),
            AccountCommand.Withdraw(gbp(600_000), "w-1"),
        )

        spent.decide(id, AccountCommand.Refund(TransferId("t-1"), gbp(400_000)), noon).shouldBeRight() shouldHaveSize 1
        spent.after(AccountCommand.Refund(TransferId("t-1"), gbp(400_000)))
            .decide(id, AccountCommand.Withdraw(gbp(400_001), "w-2"), noon).shouldBeLeft() shouldBe
            AccountError.DailyLimitExceeded("acc-1", gbp(1_000_000), gbp(400_000), gbp(400_001))
    }

    @Test
    fun `a refund on a later day gives nothing back to that day`() {
        val debited = rich.after(AccountCommand.Debit(TransferId("t-1"), gbp(500_000)))
        val tomorrow = noon + day
        val next = debited
            .after(AccountCommand.Withdraw(gbp(700_000), "w-1"), now = tomorrow)
            .after(AccountCommand.Refund(TransferId("t-1"), gbp(500_000)), now = tomorrow)

        next.decide(id, AccountCommand.Withdraw(gbp(300_001), "w-2"), tomorrow).shouldBeLeft() shouldBe
            AccountError.DailyLimitExceeded("acc-1", gbp(1_000_000), gbp(300_000), gbp(300_001))
    }

    @Test
    fun `an account that cannot cover the amount says so before the limit`() {
        val poor = Account.Unopened.after(AccountCommand.Open("ada", gbp(100), "open", dailyLimit = gbp(50)))

        poor.decide(id, AccountCommand.Withdraw(gbp(200), "w"), noon).shouldBeLeft() shouldBe
            AccountError.InsufficientFunds("acc-1", gbp(100), gbp(200))
    }

    @Test
    fun `a debit refused for the limit rejects its transfer with the refusal as the reason`() {
        val refusal = AccountError.DailyLimitExceeded("a", gbp(1_000_000), gbp(0), gbp(1))
        val requested = Transfer.Unrequested.evolve(TransferEvent.Requested(AccountId("a"), AccountId("b"), gbp(1), 0))
        val moving = requested as Transfer.Moving

        moving.after(requested.next(TransferId("t-1")), refusal, 1) shouldBe TransferEvent.Rejected(refusal.message, 1)
    }
}
