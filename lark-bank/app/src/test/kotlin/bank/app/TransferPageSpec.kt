package bank.app

import bank.domain.AccountId
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val patiently = LocatorAssertions.ContainsTextOptions().setTimeout(15_000.0)

/** Bank spec 0006: a transfer followed until it settles, and a payroll. */
class TransferPageSpec {

    @Test
    fun `a transfer still pending when the page is answered is followed until it completes`() {
        TestCluster(1).running { (node) ->
            node.bank.open(AccountId("tp-a"), "Ada", gbp(10_000), "open:tp-a").shouldBeRight()
            node.bank.open(AccountId("tp-b"), "Bob", gbp(0), "open:tp-b").shouldBeRight()
            inBrowser { page ->
                // The start reaches the bank, but the page is answered as if the saga had not moved yet.
                page.route("**/transfers/*") { route: Route ->
                    if (route.request().method() == "PUT") {
                        val real = route.fetch().text()
                        route.fulfill(
                            Route.FulfillOptions().setStatus(200).setContentType("application/json")
                                .setBody(real.replace(Regex("\"status\":\"\\w+\""), "\"status\":\"Pending\"")),
                        )
                    } else {
                        route.resume()
                    }
                }
                page.signIn(node.server.baseUrl, "Ada", then = "/transfer.html")
                page.locator("#transfer input[name=from]").fill("tp-a")
                page.locator("#transfer input[name=to]").fill("tp-b")
                page.locator("#transfer input[name=amount]").fill("25.00")
                page.locator("#transfer button:not(.quiet)").click()

                assertThat(page.locator("#result")).containsText("Completed", patiently)
                page.locator("#steps li").allTextContents().map { it.substringBefore(':') } shouldBe
                    listOf("Pending", "Completed")
            }
            node.bank.balance(AccountId("tp-b")).shouldBeRight().balance shouldBe gbp(2_500)
        }
    }

    @Test
    fun `a transfer to an account that does not exist is refunded, and says why`() {
        TestCluster(1).running { (node) ->
            node.bank.open(AccountId("tp-c"), "Cy", gbp(10_000), "open:tp-c").shouldBeRight()
            inBrowser { page ->
                page.signIn(node.server.baseUrl, "Cy", then = "/transfer.html")
                page.locator("#transfer input[name=from]").fill("tp-c")
                page.locator("#transfer input[name=to]").fill("nobody")
                page.locator("#transfer input[name=amount]").fill("5")
                page.locator("#transfer button:not(.quiet)").click()
                assertThat(page.locator("#result")).containsText("Refunded: No account nobody", patiently)
            }
            node.bank.balance(AccountId("tp-c")).shouldBeRight().balance shouldBe gbp(10_000)
        }
    }

    @Test
    fun `a payroll pasted as lines credits every account on it`() {
        TestCluster(1).running { (node) ->
            listOf("pay-1", "pay-2").forEach { node.bank.open(AccountId(it), it, gbp(0), "open:$it").shouldBeRight() }
            inBrowser { page ->
                page.signIn(node.server.baseUrl, "olga", groups = "ops", then = "/payroll.html")
                page.locator("#payroll textarea").fill("pay-1, 1200.00, GBP\npay-2, 980.50, gbp")
                page.locator("#payroll button:not(.quiet)").click()
                assertThat(page.locator("#result")).containsText("Accepted: 2 credits sent", patiently)
            }
            eventually {
                node.bank.balance(AccountId("pay-1")).getOrNull()?.balance == gbp(120_000) &&
                    node.bank.balance(AccountId("pay-2")).getOrNull()?.balance == gbp(98_050)
            } shouldBe true
        }
    }
}
