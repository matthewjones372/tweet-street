package bank.app

import bank.api.Caller
import bank.api.getAccount
import bank.api.myAccounts
import bank.domain.AccountId
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.signedInAs
import io.kotest.matchers.collections.shouldBeEmpty
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private val patiently = LocatorAssertions.ContainsTextOptions().setTimeout(15_000.0)

/** Bank spec 0021's `act-as`: support sees what a customer sees, only while a grant lives, and moves nothing. */
class ActAsSpec {
    private val run = UUID.randomUUID().toString().take(8)

    @Test
    fun `support acts as Ada with a grant, sees her statement and the banner, is refused a withdrawal, and is back to themselves when it ends`() {
        val account = "ada-$run"
        TestCluster(1).running { (node) ->
            val base = node.server.baseUrl
            node.bank.open(AccountId(account), "ada", gbp(5_000), "open:$account").shouldBeRight()
            node.bank.deposit(AccountId(account), gbp(1_000), "d-$run").shouldBeRight()
            // Her accounts are the read model's, which the page asks for once: projected first, or it shows none.
            val until = System.nanoTime() + 30_000_000_000
            while (node.reads.accountsOf(Caller("ada", "ada", emptySet())).none { it.id == account }) {
                check(System.nanoTime() < until) { "Ada's account was never projected" }
                Thread.sleep(100)
            }
            val ends = Instant.now().plusSeconds(12)
            node.grants.give("act-$run", "sam", Access.ActAs("ada"), ends)

            inBrowser { page ->
                page.signIn(base, "sam", "support")
                // Nobody without a grant: Eve's name gets Sam nothing.
                page.locator("#act-as input[name=subject]").fill("eve")
                page.locator("#act-as button").click()
                assertThat(page.locator("#act-as-result")).containsText("No grant", patiently)
                assertThat(page.locator("#acting")).isHidden()

                page.locator("#act-as input[name=subject]").fill("ada")
                page.locator("#act-as button").click()
                assertThat(page.locator("#acting")).containsText("sam (support) is acting as ada until", patiently)
                assertThat(page.locator("#mine")).containsText(account, patiently)

                page.navigate("$base/account.html?id=$account")
                assertThat(page.locator("#acting")).containsText("is acting as ada", patiently)
                assertThat(page.locator("#statement tbody")).containsText("deposited", patiently)
                page.locator("#withdraw input[name=amount]").fill("10.00")
                page.locator("#withdraw button:not(.retry)").click()
                assertThat(page.locator("#withdraw-result")).containsText("read-only", patiently)

                // The grant ends, and the session with it: Sam is Sam again, and sees nothing of Ada's.
                Thread.sleep(maxOf(0, ends.plusSeconds(1).toEpochMilli() - System.currentTimeMillis()))
                page.navigate("$base/")
                assertThat(page.locator("#signed-in")).containsText("sam", patiently)
                assertThat(page.locator("#acting")).isHidden()
                assertThat(page.locator("#none")).isVisible()
            }
            node.bank.balance(AccountId(account)).shouldBeRight().balance shouldBe gbp(6_000)
        }
    }

    @Test
    fun `stopping ends acting at once, and the grant cannot be used by someone else in support`() {
        TestCluster(1).running { (node) ->
            val base = node.server.baseUrl
            node.grants.give("act-$run", "sam", Access.ActAs("ada"), Instant.now().plusSeconds(600))
            inBrowser { page ->
                page.signIn(base, "gil", "support")
                page.locator("#act-as input[name=subject]").fill("ada")
                page.locator("#act-as button").click()
                assertThat(page.locator("#act-as-result")).containsText("No grant", patiently)
            }
            inBrowser { page ->
                page.signIn(base, "sam", "support")
                page.locator("#act-as input[name=subject]").fill("ada")
                page.locator("#act-as button").click()
                assertThat(page.locator("#acting")).containsText("is acting as ada", patiently)
                page.locator("#stop-acting").click()
                assertThat(page.locator("#signed-in")).containsText("sam", patiently)
                assertThat(page.locator("#acting")).isHidden()
            }
        }
    }

    @Test
    fun `a token acting as Ada with no grant behind it sees nothing of hers`() {
        TestCluster(1).running { (node) ->
            node.bank.open(AccountId("ada-$run"), "ada", gbp(5_000), "open:ada-$run").shouldBeRight()
            apiClient(node.server.baseUrl, JacksonCodecs).use { client ->
                val mal = client.signedInAs(TestIdentity.token("ada", actor = "mal"))
                mal.response(getAccount, "ada-$run").status shouldBe 404
                mal.call(myAccounts, Unit).shouldBeEmpty()
            }
        }
    }
}
