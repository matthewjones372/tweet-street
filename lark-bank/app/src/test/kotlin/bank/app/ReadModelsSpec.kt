package bank.app

import bank.domain.AccountEvent
import io.github.matthewjones372.lark.app.liquibase.migrate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * Spec 0014: read models written by partitions at once. Two writers can hold the same account's events, as after a
 * range of slices moves between journal databases; the statement line's key decides which of them counts each one.
 */
class ReadModelsSpec {

    private val data: DataSource = PGSimpleDataSource().apply {
        setURL(TestPostgres.fresh())
    }.also { source -> migrate(source, "db/read-models.sql") }
    private val reads = JdbcReadModels(data)

    private val accounts = (1..50).map { "acc-$it" }
    private val batch = accounts.flatMap { id ->
        listOf(Sequenced(id, 1, AccountEvent.Opened("owner", gbp(1_000), "open:$id", 0) as AccountEvent)) +
            (2L..20L).map { sequence -> Sequenced(id, sequence, AccountEvent.Deposited(gbp(10), "d:$id:$sequence", 0)) }
    }

    @Test
    fun `the same batch written by eight writers at once is counted once, and every balance is where it ends`() {
        val go = CountDownLatch(1)
        val written = Executors.newFixedThreadPool(8).use { writers ->
            val each = (1..8).map { writers.submit<Int> { go.await().let { reads.project(batch) } } }
            go.countDown()
            each.map { it.get() }
        }

        written.sum() shouldBe batch.size
        ledgerEvents() shouldBe batch.size.toLong()
        accounts.forEach { id -> balance(id) shouldBe (BigDecimal("11.90") to 20L) }
    }

    @Test
    fun `a batch handed over twice changes nothing the second time`() {
        reads.project(batch) shouldBe batch.size
        reads.project(batch) shouldBe 0
        reads.project(batch.filter { it.sequence <= 10 }) shouldBe 0
        ledgerEvents() shouldBe batch.size.toLong()
        balance(accounts.first()) shouldBe (BigDecimal("11.90") to 20L)
    }

    private fun ledgerEvents(): Long = data.connection.use { connection ->
        connection.createStatement().use { query ->
            query.executeQuery("select coalesce(sum(events), 0) from ledger_totals").use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    private fun balance(id: String): Pair<BigDecimal, Long> = data.connection.use { connection ->
        connection.prepareStatement("select balance, last_seq from account_balance where account_id = ?").use { query ->
            query.setString(1, id)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getBigDecimal(1) to rows.getLong(2)
            }
        }
    }
}
