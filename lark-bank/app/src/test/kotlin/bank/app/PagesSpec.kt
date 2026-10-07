package bank.app

import bank.domain.AccountId
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Bank spec 0006: the pages are served beside the API, and every endpoint still answers as itself. */
class PagesSpec {

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun get(url: String, token: String? = null): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).apply { token?.let { header("Authorization", "Bearer $it") } }.GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `the pages are served beside the API, and an endpoint still answers as itself`() {
        TestCluster(1).running { (node) ->
            val base = node.server.baseUrl
            // Nobody signed in is sent to sign in first; the API's own document is open.
            get("$base/").let { it.statusCode() shouldBe 302; it.headers().firstValue("location").get() shouldBe "/login?return=%2F" }
            get("$base/api-docs").statusCode() shouldBe 200
            get("$base/accounts/nobody").statusCode() shouldBe 401
            get("$base/accounts/nobody", TestIdentity.token("ada")).let { answer ->
                answer.statusCode() shouldBe 404
                answer.body() shouldContain "No account nobody"
            }

            node.bank.open(AccountId("bobs"), "bob", gbp(5_000), "open:bobs").shouldBeRight()
            inBrowser { page ->
                page.signIn(base, "ada")
                // Signed in as Ada, Bob's account is not there to see.
                page.navigate("$base/account.html?id=bobs")
                page.waitForSelector("#missing:has-text('No account bobs')")
                page.navigate("$base/")
                page.title() shouldBe "Lark Bank"
                page.locator("header nav a[aria-current=page]").textContent() shouldBe "Accounts"
                page.waitForSelector("#signed-in:has-text('ada')")

                page.navigate("$base/ops")
                page.title() shouldBe "Ops · Lark Bank"

                page.locator("#sign-out").click()
                page.waitForSelector("#sign-in")
                page.url() shouldContain "${TestIdentity.issuer.url}/authorize"
            }
        }
    }
}
