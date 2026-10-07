package bank.app

import bank.domain.AccountId
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

private val patiently = LocatorAssertions.ContainsTextOptions().setTimeout(15_000.0)

/** Bank spec 0006: an account's page, and a retry that moves money once. */
class AccountPageSpec {

    @Test
    fun `an account opened on the page, withdrawn from with a lost answer, moves the money once`() {
        TestCluster(1).running { (node) ->
            val base = node.server.baseUrl
            inBrowser { page ->
                page.signIn(base, "ada")
                page.locator("#open input[name=id]").fill("acc-ui-1")
                page.locator("#open input[name=initial]").fill("100.00")
                page.locator("#open button").click()
                assertThat(page.locator("#balance")).containsText("£100.00", patiently)

                // The first withdrawal reaches the bank, but its answer is lost: the page is told 503.
                val sent = AtomicInteger()
                page.route("**/withdrawals") { route: Route ->
                    if (sent.incrementAndGet() == 1) {
                        route.fetch()
                        route.fulfill(
                            Route.FulfillOptions().setStatus(503).setContentType("application/json")
                                .setBody("""{"message":"The bank could not answer in time"}"""),
                        )
                    } else {
                        route.resume()
                    }
                }
                page.locator("#withdraw input[name=amount]").fill("30.00")
                page.locator("#withdraw button:not(.retry)").click()
                assertThat(page.locator("#withdraw-result")).containsText("Try again sends reference", patiently)

                page.locator("#withdraw .retry").click()
                assertThat(page.locator("#withdraw-result")).containsText("Withdrew £30.00", patiently)
                assertThat(page.locator("#balance")).containsText("£70.00", patiently)
                sent.get() shouldBe 2
            }
            node.bank.balance(AccountId("acc-ui-1")).shouldBeRight().balance shouldBe gbp(7_000)
        }
    }

    @Test
    fun `more than the balance is refused with the bank's own reason, and the statement shows what moved`() {
        TestCluster(1).running { (node) ->
            node.bank.open(AccountId("acc-ui-2"), "Bob", gbp(5_000), "open:acc-ui-2").shouldBeRight()
            node.bank.deposit(AccountId("acc-ui-2"), gbp(1_000), "d-1").shouldBeRight()
            inBrowser { page ->
                page.signIn(node.server.baseUrl, "Bob", then = "/account.html?id=acc-ui-2")
                assertThat(page.locator("#balance")).containsText("60.00", patiently)

                page.locator("#withdraw input[name=amount]").fill("1000")
                page.locator("#withdraw button:not(.retry)").click()
                assertThat(page.locator("#withdraw-result")).containsText("has 60.00 GBP, not 1000.00 GBP", patiently)
                assertThat(page.locator("#withdraw .retry")).isHidden()

                assertThat(page.locator("#statement tbody")).containsText("deposited", patiently)
                page.locator("#statement tbody tr").count() shouldBe 2
            }
        }
    }

    @Test
    fun `money moved without the page shows in its statement, with nothing clicked`() {
        TestCluster(1).running { (node) ->
            node.bank.open(AccountId("acc-ui-3"), "Cy", gbp(5_000), "open:acc-ui-3").shouldBeRight()
            inBrowser { page ->
                page.signIn(node.server.baseUrl, "Cy", then = "/account.html?id=acc-ui-3")
                assertThat(page.locator("#statement tbody")).containsText("opened", patiently)

                node.bank.deposit(AccountId("acc-ui-3"), gbp(1_000), "elsewhere").shouldBeRight()
                assertThat(page.locator("#statement tbody")).containsText("elsewhere", patiently)
            }
        }
    }

    @Test
    fun `an account that does not exist says so`() {
        TestCluster(1).running { (node) ->
            inBrowser { page ->
                page.signIn(node.server.baseUrl, "ada", then = "/account.html?id=nobody")
                assertThat(page.locator("#missing")).containsText("No account nobody", patiently)
                assertThat(page.locator("#movements")).isHidden()
            }
        }
    }
}
