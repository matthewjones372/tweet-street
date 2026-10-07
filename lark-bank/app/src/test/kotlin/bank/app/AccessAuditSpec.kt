package bank.app

import bank.access.fga.Tuple
import bank.api.Because
import bank.api.accountViewers
import bank.api.personSees
import bank.api.statement
import bank.domain.AccountId
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Bank spec 0022's `access-audit`: who can see an account, what a person can see, and why, on the access page. */
class AccessAuditSpec {
    private val run = UUID.randomUUID().toString().take(8)

    @Test
    fun `an auditor sees Bob's grant on the account, with the approval it came from, and not after it expires`() {
        val store = "audit-$run"
        val fga = TestAccess.store(store)
        val acc = "acc-$run"
        val approval = "approval-$run"
        TestCluster(1, extra = TestAccess.config(store)).running { (node) ->
            val base = node.server.baseUrl
            node.bank.open(AccountId(acc), "ada", gbp(5_000), "open:$acc").shouldBeRight()
            // OpenFGA keeps a condition's time to the second; the grant is given to the same second.
            val expires = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.SECONDS)
            node.grants.give(approval, "bob", Access.View(acc), expires)
            // What access-sync writes from the Opened, the approved grant and Pocket ID's groups, written here instead.
            fga.write(
                listOf(
                    Tuple("person:ada", "owner", "account:$acc"),
                    Tuple("bank:lark", "bank", "account:$acc"),
                    Tuple("person:bob", "supporter", "account:$acc", expires),
                    Tuple("group:auditor#member", "auditor", "bank:lark"),
                    Tuple("person:cy", "member", "group:auditor"),
                ),
            )

            apiClient(base, JacksonCodecs).use { client ->
                // The owner's reason is the event that opened the account, once the statements have it.
                eventually("the account's opening in the statements") {
                    client.calling("cy", "auditor").call(statement, In3(acc, 50, null)).isNotEmpty()
                }
                val viewers = client.calling("cy", "auditor").call(accountViewers, acc)
                viewers.map { it.person to it.relation } shouldContainExactlyInAnyOrder
                    listOf("ada" to "owner", "bob" to "supporter", "cy" to "auditor")
                viewers.single { it.person == "bob" }.let { bob ->
                    bob.approval shouldBe approval
                    bob.untilMillis shouldBe expires.toEpochMilli()
                }
                viewers.single { it.person == "ada" }.event shouldBe "opened, event 1 of $acc"
                client.calling("cy", "auditor").call(personSees, "bob").map(Because::account) shouldBe listOf(acc)

                // Asked by someone who is not ops, an admin or an auditor: refused, whoever they are.
                client.calling("ada").response(accountViewers, acc).status shouldBe 403
                client.calling("bob", "support").response(personSees, "bob").status shouldBe 403
            }

            inBrowser { page ->
                page.signIn(base, "cy", "auditor", then = "/access.html?account=$acc")
                page.waitForSelector("#viewers tr[data-person=bob]:has-text('$approval')")
                page.waitForSelector("#viewers tr[data-person=ada]:has-text('opened')")
                page.waitForSelector("#viewers tr[data-person=cy]:has-text('auditor')")

                page.navigate("$base/access.html?person=bob")
                page.waitForSelector("#sees tr[data-account=$acc]:has-text('$approval')")

                // Once the grant has expired, Bob is no longer one who can see it, though the relationship is still there.
                Thread.sleep(maxOf(0, expires.toEpochMilli() - System.currentTimeMillis()) + 1_000)
                page.navigate("$base/access.html?account=$acc")
                page.waitForSelector("#viewers tr[data-person=ada]")
                page.locator("#viewers tr[data-person=bob]").count() shouldBe 0
                page.navigate("$base/access.html?person=bob")
                page.waitForSelector("#sees-none")
            }
        }
    }

    private fun eventually(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + 30_000_000_000
        while (!done()) {
            check(System.nanoTime() < until) { "never: $what" }
            Thread.sleep(200)
        }
    }
}
