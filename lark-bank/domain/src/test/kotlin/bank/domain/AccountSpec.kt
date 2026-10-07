package bank.domain

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class AccountSpec {
    private val id = AccountId("acc-1")

    private fun Account.after(vararg commands: AccountCommand): Account =
        commands.fold(this) { state, command ->
            state.decide(id, command, now = 0).shouldBeRight().fold(state, Account::evolve)
        }

    private val opened = Account.Unopened.after(AccountCommand.Open("ada", gbp(1_000), "open"))

    @Test
    fun `an account opens with its initial balance`() {
        opened.balanceOf(id).shouldBeRight().balance shouldBe gbp(1_000)
    }

    @Test
    fun `nothing but opening reaches an account that is not open`() {
        Account.Unopened.decide(id, AccountCommand.Deposit(gbp(1), "d"), 0)
            .shouldBeLeft() shouldBe AccountError.NoSuchAccount("acc-1")
    }

    @Test
    fun `an account cannot be opened again for someone else`() {
        opened.decide(id, AccountCommand.Open("bob", gbp(1), "again"), 0)
            .shouldBeLeft() shouldBe AccountError.AlreadyOpen("acc-1")
    }

    @Test
    fun `opening again for the same owner is a retry and writes nothing`() {
        opened.decide(id, AccountCommand.Open("ada", gbp(1), "again"), 0).shouldBeRight().shouldBeEmpty()
    }

    @Test
    fun `a withdrawal larger than the balance is refused and nothing is written`() {
        opened.decide(id, AccountCommand.Withdraw(gbp(1_001), "w"), 0)
            .shouldBeLeft() shouldBe AccountError.InsufficientFunds("acc-1", gbp(1_000), gbp(1_001))
    }

    @Test
    fun `a zero or negative amount never moves`() {
        opened.decide(id, AccountCommand.Deposit(gbp(0), "d"), 0).shouldBeLeft().shouldBeInstanceOf<AccountError.InvalidAmount>()
        opened.decide(id, AccountCommand.Debit(TransferId("t"), gbp(-5)), 0).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.InvalidAmount>()
    }

    @Test
    fun `the same reference twice moves the money once`() {
        val once = opened.after(AccountCommand.Deposit(gbp(250), "pay-7"))
        once.decide(id, AccountCommand.Deposit(gbp(250), "pay-7"), 0).shouldBeRight().shouldBeEmpty()
        once.balanceOf(id).shouldBeRight().balance shouldBe gbp(1_250)
    }

    @Test
    fun `a transfer's legs are idempotent by transfer id`() {
        val debited = opened.after(AccountCommand.Debit(TransferId("t1"), gbp(400)))
        debited.decide(id, AccountCommand.Debit(TransferId("t1"), gbp(400)), 0).shouldBeRight().shouldBeEmpty()
        debited.after(AccountCommand.Refund(TransferId("t1"), gbp(400))).balanceOf(id).shouldBeRight().balance shouldBe
            gbp(1_000)
    }

    @Test
    fun `an account keeps the currency it opened in, and refuses any other before anything moves`() {
        val yen = Money.ofMinor(500, JPY)
        opened.decide(id, AccountCommand.Deposit(yen, "d"), 0).shouldBeLeft() shouldBe
            AccountError.CurrencyMismatch("acc-1", "GBP", "JPY")
        opened.decide(id, AccountCommand.Withdraw(yen, "w"), 0).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.CurrencyMismatch>()
        opened.decide(id, AccountCommand.Credit(TransferId("t"), yen), 0).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.CurrencyMismatch>()
        opened.decide(id, AccountCommand.Debit(TransferId("t"), yen), 0).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.CurrencyMismatch>()
    }

    @Test
    fun `an account in bitcoin moves to the satoshi`() {
        val wallet = Account.Unopened.after(AccountCommand.Open("ada", Money.ofMinor(0, BTC), "open"))
            .after(AccountCommand.Deposit(Money.ofMinor(125_000, BTC), "d"))
        wallet.balanceOf(id).shouldBeRight().balance.toString() shouldBe "0.00125000 BTC"
    }

    @Test
    fun `a transfer's leg retried after hundreds of other commands is still applied once`() {
        val debited = opened.after(AccountCommand.Debit(TransferId("t1"), gbp(400)))
        val busy = (1..Account.RECENT + 44).fold(debited) { state, n -> state.after(AccountCommand.Deposit(gbp(1), "d$n")) }
        busy.decide(id, AccountCommand.Debit(TransferId("t1"), gbp(400)), 0).shouldBeRight().shouldBeEmpty()
        busy.decide(id, AccountCommand.Credit(TransferId("t1"), gbp(400)), 0).shouldBeRight().size shouldBe 1
    }

    @Test
    fun `a settled transfer's legs are closed once, and an account with none writes nothing`() {
        val debited = opened.after(AccountCommand.Debit(TransferId("t1"), gbp(400)))
        val closed = debited.after(AccountCommand.Close(TransferId("t1")))
        (closed as Account.Open).legs.shouldBeEmpty()
        closed.decide(id, AccountCommand.Close(TransferId("t1")), 0).shouldBeRight().shouldBeEmpty()
        opened.decide(id, AccountCommand.Close(TransferId("t9")), 0).shouldBeRight().shouldBeEmpty()
    }

    @Test
    fun `only the most recent references are remembered`() {
        val busy = (1..Account.RECENT + 10).fold(opened) { state, n ->
            state.after(AccountCommand.Deposit(gbp(1), "d$n"))
        } as Account.Open
        busy.recent.size shouldBe Account.RECENT
        busy.recent.last() shouldBe "d${Account.RECENT + 10}"
    }
}
