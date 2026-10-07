package bank.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class TransferSpec {
    private val id = TransferId("t1")
    private val requested: Transfer =
        Transfer.Unrequested.evolve(TransferEvent.Requested(AccountId("a"), AccountId("b"), gbp(500), 1))

    private fun Transfer.step(refusal: AccountError? = null): Transfer {
        val moving = this as Transfer.Moving
        val event = moving.after(next(id), refusal, now = 2) ?: return this
        return evolve(event)
    }

    @Test
    fun `a transfer debits then credits then is done`() {
        requested.next(id) shouldBe Step.DebitSource(AccountId("a"), AccountCommand.Debit(id, gbp(500)))
        val debited = requested.step()
        debited.next(id) shouldBe Step.CreditDestination(AccountId("b"), AccountCommand.Credit(id, gbp(500)))
        val done = debited.step() as Transfer.Moving
        done.status shouldBe TransferStatus.Completed
        done.next(id) shouldBe Step.Done
    }

    @Test
    fun `a refused debit rejects the transfer and nothing is undone`() {
        val rejected = requested.step(AccountError.InsufficientFunds("a", gbp(1), gbp(500))) as Transfer.Moving
        rejected.status shouldBe TransferStatus.Rejected
        rejected.next(id) shouldBe Step.Done
    }

    @Test
    fun `a refused credit refunds the source`() {
        val refunding = requested.step().step(AccountError.NoSuchAccount("b"))
        refunding.next(id).shouldBeInstanceOf<Step.RefundSource>()
        (refunding.step() as Transfer.Moving).status shouldBe TransferStatus.Refunded
    }

    @Test
    fun `with screening on, a transfer screens before any money moves, and debits once approved`() {
        requested.next(id, screening = true) shouldBe Step.Screen(AccountId("a"), AccountId("b"), gbp(500), requestedAt = 1)
        val approved = requested.evolve(
            TransferEvent.Screened(ScreeningOutcome.Approved, null, null, "no rule held", answered = true, atMillis = 2),
        )
        approved.next(id, screening = true) shouldBe Step.DebitSource(AccountId("a"), AccountCommand.Debit(id, gbp(500)))
    }

    @Test
    fun `a declined transfer is rejected with the rule that declined it, and nothing moves`() {
        val declined = requested.evolve(
            TransferEvent.Screened(ScreeningOutcome.Declined, "large-transfer", 3, "amount >= 1000.00", answered = true, atMillis = 2),
        ) as Transfer.Moving
        declined.status shouldBe TransferStatus.Rejected
        declined.reason shouldBe "Declined by large-transfer, version 3"
        declined.settledAt shouldBe 2L
        declined.next(id, screening = true) shouldBe Step.Done
    }

    @Test
    fun `a transfer screening did not answer for follows the bank's policy, and says so`() {
        val approved = requested.evolve(unanswered(WhenUnanswered.Approve, now = 2))
        approved.next(id, screening = true).shouldBeInstanceOf<Step.DebitSource>()
        val declined = requested.evolve(unanswered(WhenUnanswered.Decline, now = 2)) as Transfer.Moving
        declined.reason shouldBe "Declined: the check did not answer in time"
        unanswered(WhenUnanswered.Approve, now = 2).answered shouldBe false
    }

    @Test
    fun `with screening off, a transfer debits as it always did, and one already screened is not asked again`() {
        requested.next(id, screening = false).shouldBeInstanceOf<Step.DebitSource>()
        val approved = requested.evolve(unanswered(WhenUnanswered.Approve, now = 2))
        approved.next(id, screening = true).shouldBeInstanceOf<Step.DebitSource>()
    }

    @Test
    fun `a refused refund leaves the saga refunding so it tries again`() {
        val refunding = requested.step().step(AccountError.NoSuchAccount("b"))
        (refunding.step(AccountError.NoSuchAccount("a")) as Transfer.Moving).status shouldBe TransferStatus.Refunding
    }
}
