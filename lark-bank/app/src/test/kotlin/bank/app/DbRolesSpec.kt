package bank.app

import bank.api.Caller
import bank.domain.AccountId
import bank.domain.TransferId
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Bank spec 0021's `db-roles`: the database is the second fence, below the rules. */
class DbRolesSpec {
    private val run = UUID.randomUUID().toString().take(8)
    private val ada1 = "ada-1-$run"
    private val ada2 = "ada-2-$run"
    private val bob1 = "bob-1-$run"

    private fun eventually(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + 30_000_000_000
        while (!done()) {
            check(System.nanoTime() < until) { "never: $what" }
            Thread.sleep(100)
        }
    }

    /** Ada's two accounts and Bob's one, a movement in each, and a transfer from Ada to Bob, all projected. */
    private fun accounts(node: TestNode) {
        node.bank.open(AccountId(ada1), "ada", gbp(5_000), "open:$ada1").shouldBeRight()
        node.bank.open(AccountId(ada2), "ada", gbp(1_000), "open:$ada2").shouldBeRight()
        node.bank.open(AccountId(bob1), "bob", gbp(0), "open:$bob1").shouldBeRight()
        node.bank.deposit(AccountId(bob1), gbp(700), "d-$run").shouldBeRight()
        node.bank.transfer(TransferId("t-$run"), AccountId(ada1), AccountId(bob1), gbp(100)).shouldBeRight()
        // Bob's opening, deposit and credit; Ada's opening and debit.
        eventually("every line projected") {
            node.reads.statement(auditor, bob1, 50, null).size == 3 && node.reads.statement(auditor, ada1, 50, null).size == 2
        }
    }

    private val auditor = Caller("aud", "aud", setOf("auditor"))

    /** In a transaction rolled back after: as [role], with `bank.caller` and `bank.auditor` set as the bank sets them. */
    private fun <A> Connection.seenAs(role: String, caller: String?, auditing: Boolean = false, query: (Connection) -> A): A {
        autoCommit = false
        try {
            prepareStatement("select set_config('role', ?, true), set_config('bank.caller', ?, true), set_config('bank.auditor', ?, true)")
                .use {
                    it.setString(1, role)
                    it.setString(2, caller ?: "")
                    it.setString(3, if (auditing) "on" else "off")
                    it.execute()
                }
            return query(this)
        } finally {
            rollback()
            autoCommit = true
        }
    }

    private fun Connection.accountsIn(sql: String): Set<String> =
        createStatement().use { it.executeQuery(sql).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toSet() } }

    @Test
    fun `as bank_reader with bank caller set to Ada, select star from statement_line returns only her rows`() {
        val database = TestPostgres.fresh()
        TestCluster(1, database = database).running { (node) ->
            accounts(node)
            node.grants.give("rls-$run", "sam", Access.View(bob1), Instant.now().plusSeconds(600))
            DriverManager.getConnection(database).use { connection ->
                // A query that forgot its `where`: what each caller gets back is theirs.
                val lines = "select account_id from statement_line"
                connection.seenAs("bank_reader", "ada") { it.accountsIn(lines) } shouldBe setOf(ada1, ada2)
                connection.seenAs("bank_reader", "bob") { it.accountsIn(lines) } shouldBe setOf(bob1)
                connection.seenAs("bank_reader", "eve") { it.accountsIn(lines) }.shouldBeEmpty()
                connection.seenAs("bank_reader", null) { it.accountsIn(lines) }.shouldBeEmpty()
                // Support sees the one account a grant names, and an auditor every one.
                connection.seenAs("bank_reader", "sam") { it.accountsIn(lines) } shouldBe setOf(bob1)
                connection.seenAs("auditor_ro", "aud", auditing = true) { it.accountsIn(lines) } shouldBe setOf(ada1, ada2, bob1)
                connection.seenAs("auditor_ro", "aud") { it.accountsIn(lines) }.shouldBeEmpty()

                // Balances the same way, and a transfer is seen by whoever owns either end of it.
                connection.seenAs("bank_reader", "ada") { it.accountsIn("select account_id from account_balance") } shouldBe setOf(ada1, ada2)
                val transfers = "select transfer_id from transfer_status"
                connection.seenAs("bank_reader", "ada") { it.accountsIn(transfers) } shouldBe setOf("t-$run")
                connection.seenAs("bank_reader", "bob") { it.accountsIn(transfers) } shouldBe setOf("t-$run")
                connection.seenAs("bank_reader", "eve") { it.accountsIn(transfers) }.shouldBeEmpty()

                // The reader reads: it writes nothing, and it cannot read the journal at all.
                connection.seenAs("bank_reader", "ada") {
                    shouldThrow<SQLException> { it.createStatement().use { s -> s.executeUpdate("update account_balance set balance = 0") } }
                }
                connection.seenAs("bank_reader", "ada") {
                    shouldThrow<SQLException> { it.createStatement().use { s -> s.executeQuery("select count(*) from lark_journal") } }
                }
            }
        }
    }

    @Test
    fun `the bank reads a statement through the same fence, so a read the rules let slip returns nothing`() {
        TestCluster(1).running { (node) ->
            accounts(node)
            node.reads.statement(Caller("ada", "Ada", emptySet()), ada1, 50, null).size shouldBe 2
            node.reads.statement(Caller("ada", "Ada", emptySet(), actor = "sam"), ada1, 50, null).size shouldBe 2
            // Asked for Ada's account as Eve, as a handler that forgot its rule would: the database answers nothing.
            node.reads.statement(Caller("eve", "Eve", emptySet()), ada1, 50, null).shouldBeEmpty()
            node.reads.statement(Caller("sam", "Sam", setOf("support")), bob1, 50, null).shouldBeEmpty()
            node.grants.give("rls-$run", "sam", Access.View(bob1), Instant.now().plusSeconds(600))
            node.reads.statement(Caller("sam", "Sam", setOf("support")), bob1, 50, null).size shouldBe 3
            node.reads.accountsOf(Caller("ada", "Ada", emptySet())).map { it.id } shouldBe listOf(ada1, ada2)
        }
    }
}
