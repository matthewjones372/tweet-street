package bank.app

import bank.domain.AccountId
import bank.domain.TransferEvent
import bank.protocol.Kinds
import bank.protocol.TransferEvents
import io.github.matthewjones372.lark.actor.PersistenceId
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Spec 0002: a saga whose node died after it was requested is in the journal and nowhere else. Nobody asks about it,
 * so only the sweeper can wake it — and once woken it reads its next step off its state and finishes.
 */
class SweeperSpec {

    @Test
    fun `a stranded transfer is found and finished without anyone asking about it`() {
        TestCluster(1).running { (node) ->
            node.bank.open(AccountId("a"), "Ada", gbp(1_000), "open:a").shouldBeRight()
            node.bank.open(AccountId("b"), "Bob", gbp(0), "open:b").shouldBeRight()

            // What a node leaves behind when it dies the moment after persisting the request.
            val requested = TransferEvent.Requested(AccountId("a"), AccountId("b"), gbp(300), now())
            node.persistence.journal.append(PersistenceId(Kinds.TRANSFER, "t-stranded"), 0, listOf(TransferEvents.encode(requested)))
                .shouldBeRight()

            withClue("the sweeper nudges it and it completes: ${node.reads.transfers()}") {
                eventually(30) { node.reads.transfers().completed == 1L } shouldBe true
            }
            node.bank.balance(AccountId("a")).shouldBeRight().balance shouldBe gbp(700)
            node.bank.balance(AccountId("b")).shouldBeRight().balance shouldBe gbp(300)
            eventually { node.reads.ledger().conserved } shouldBe true
        }
    }
}
