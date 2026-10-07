package bank.app

import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import io.github.matthewjones372.lark.parMap
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Spec 0001: money moved between accounts on three nodes is never created, lost or moved twice, with the journal
 * split across two databases (spec 0004), so a transfer's two accounts are often in different ones.
 */
class ConservationSpec {

    private val accounts = (1..40).map { AccountId("acc-$it") }
    private val opening = gbp(10_000)

    @Test
    fun `thousands of transfers across three nodes and two journal databases leave every penny where the ledger says`() {
        TestCluster(3, journals = 2).running { nodes -> across(nodes) }
    }

    private fun across(nodes: List<TestNode>) {
        withClue("every node sees three members up") {
            eventually { nodes.all { node -> node.cluster.view.members.count { it.status.name == "Up" } == 3 } } shouldBe true
        }
        accounts.forEachIndexed { i, id ->
            nodes[i % nodes.size].bank.open(id, "owner-$i", opening, "open:$id").shouldBeRight()
        }

        val random = Random(42)
        val transfers = (1..2_000).map { n ->
            Triple(TransferId("t-$n"), accounts.random(random), accounts.random(random)) to gbp(random.nextLong(1, 800))
        }.filter { (ids, _) -> ids.second != ids.third }

        // Many at once, each from whichever node it lands on.
        val answered = parMap(transfers.chunked(transfers.size / 16)) { chunk ->
            chunk.mapIndexed { i, (ids, amount) ->
                nodes[i % nodes.size].bank.transfer(ids.first, ids.second, ids.third, amount)
            }
        }.flatten()

        answered.count { it.isLeft() } shouldBe 0

        val reads = nodes.first().reads
        withClue("every transfer settles and the read models catch up: ${reads.transfers()} ${reads.ledger()}") {
            eventually(60) {
                val counts = reads.transfers()
                counts.pending == 0L && counts.completed + counts.rejected + counts.refunded == transfers.size.toLong() &&
                    reads.ledger().conserved
            } shouldBe true
        }

        val balances = accounts.sumOf { id -> nodes.first().bank.balance(id).shouldBeRight().balance.amount }
        balances shouldBe opening.amount * accounts.size.toBigDecimal()
        // Statements are their own projection, partitioned apart from transfers' status (spec 0014): they may trail it
        // for a moment, as a worker that moved resumes from its offset, and then agree.
        withClue("the statements catch up with every account: ${reads.books()}") {
            eventually(30) { reads.books().single().balances == balances } shouldBe true
        }
        val books = reads.books().single()
        books.currency shouldBe GBP
        books.inFlight.signum() shouldBe 0

        withClue("both journal databases hold accounts") {
            nodes.first().persistence.journal.feeds.forEach { (_, feed) -> feed.after("account", 0, 1).size shouldBe 1 }
        }

        // A settled transfer answers as it settled, from any node.
        val (first, _) = transfers.first()
        val status = nodes.last().bank.transferStatus(first.first).shouldBeRight().status
        (status == TransferStatus.Completed || status == TransferStatus.Rejected) shouldBe true
    }
}
