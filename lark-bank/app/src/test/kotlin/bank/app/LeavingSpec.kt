package bank.app

import bank.domain.AccountId
import bank.domain.TransferId
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.parMap
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Spec 0001: a node leaving while transfers run through the others. Its shards move, its entities recover from the
 * journal on their new owners, and a saga caught mid-way is finished by the sweeper — so the books still balance.
 */
class LeavingSpec {

    private val accounts = (1..30).map { AccountId("acc-$it") }
    private val opening = gbp(10_000)

    @Test
    fun `a node leaving mid-run loses no money and doubles none`() {
        val cluster = TestCluster(3, journals = 2)
        cluster.node(0).use { one: TestNode ->
            cluster.node(1).use { two: TestNode ->
                // Started after the others, so its migrations find the schema already there.
                val third = Detached(cluster.node(2))
                val leaving = third.awaitUp()
                run(listOf(one, two), leaving) { third.stop() }
            }.shouldBeRight()
        }.shouldBeRight()
    }

    private fun run(staying: List<TestNode>, leaving: TestNode, leave: () -> Unit) {
        val all = staying + leaving
        eventually { all.all { node -> node.cluster.view.members.count { it.status.name == "Up" } == 3 } } shouldBe true
        accounts.forEachIndexed { i, id -> all[i % all.size].bank.open(id, "owner-$i", opening, "open:$id").shouldBeRight() }

        val random = Random(7)
        val sent = AtomicInteger()
        val batches = (1..8).map { batch ->
            (1..150).map { n -> Triple(TransferId("t-$batch-$n"), accounts.random(random), accounts.random(random)) }
                .filter { it.second != it.third }
        }

        // The first half of the load through every node, then the third leaves while the rest runs through the two.
        parMap(batches.take(4)) { batch ->
            batch.forEachIndexed { i, (id, from, to) -> all[i % all.size].bank.transfer(id, from, to, gbp(100)); sent.incrementAndGet() }
        }
        val leaver = Thread.ofPlatform().start(leave)
        parMap(batches.drop(4)) { batch ->
            batch.forEachIndexed { i, (id, from, to) ->
                // A call that lands mid-move may be unavailable; the caller retries with the same id, as a client would.
                eventually(20) { staying[i % staying.size].bank.transfer(id, from, to, gbp(100)).isRight() }
                sent.incrementAndGet()
            }
        }
        leaver.join()

        val reads = staying.first().reads
        withClue("all ${sent.get()} transfers settle and the ledger balances: ${reads.transfers()} ${reads.ledger()}") {
            eventually(90) {
                val counts = reads.transfers()
                counts.pending == 0L && counts.completed + counts.rejected + counts.refunded == sent.get().toLong() &&
                    reads.ledger().conserved
            } shouldBe true
        }
        accounts.sumOf { id -> staying.first().bank.balance(id).shouldBeRight().balance.amount } shouldBe
            opening.amount * accounts.size.toBigDecimal()
    }
}
