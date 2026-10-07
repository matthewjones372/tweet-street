package bank.approvals.app

import bank.approvals.api.AppliedBody
import bank.approvals.api.NewRequest
import bank.approvals.api.RequestView
import bank.approvals.api.applied
import bank.approvals.api.ask
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.UUID

/** A headless Chromium for the length of [block], the one installed on this machine; none is downloaded. */
fun <A> withBrowser(block: (Browser) -> A): A {
    val env = buildMap {
        put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
        System.getenv("PLAYWRIGHT_BROWSERS_PATH")?.let { put("PLAYWRIGHT_BROWSERS_PATH", it) }
    }
    return Playwright.create(Playwright.CreateOptions().setEnv(env)).use { playwright ->
        val options = BrowserType.LaunchOptions()
        System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
        playwright.chromium().launch(options).use(block)
    }
}

/** A page of its own for one person, with their own cookies: signed in through the test issuer's form, as [subject]. */
fun Browser.signedIn(base: String, subject: String, groups: String, then: String = "/"): Page {
    val page = newContext().newPage()
    page.navigate(base + then)
    page.locator("#subject").fill(subject)
    page.locator("#groups").fill(groups)
    page.locator("#sign-in").click()
    // A predicate, not a pattern: a URL pattern reads the `?` of `request.html?id=…` as a wildcard.
    page.waitForURL({ url: String -> url == base + then })
    return page
}

/** lark-bank spec 0019's approvals-pages: a request asked, talked over, approved by two and applied, through the pages. */
class PagesSpec {
    private val run = UUID.randomUUID().toString().take(8)

    @Test
    fun `the requester and two approvers sign in, talk it over, both approve, and the page shows the diff, the thread and it applied`() {
        TestCluster(1).node(0).use { node: TestNode ->
            val base = node.server.baseUrl
            val view: RequestView = node.calling("rae", "engineers").outcome(
                ask,
                NewRequest(
                    "test.change", "test/large-transfer-$run", "large-transfer, version 4",
                    before = "when amount is at least 1000\nthen decline",
                    after = "when amount is at least 500\nthen decline",
                    facts = mapOf("change" to "tighten"),
                    impact = "7 declined last week, 31 would have been",
                ),
            ).shouldBeOk()
            val page = "/request.html?id=${view.id}"

            withBrowser { browser ->
                // Ada finds it waiting on her, sees what would change, and asks about it.
                val ada = browser.signedIn(base, "ada", "risk")
                assertThat(ada.locator("#waiting li")).containsText("large-transfer, version 4")
                assertThat(ada.locator("#mine li")).hasCount(0)
                ada.locator("#waiting a", Page.LocatorOptions().setHasText("large-transfer, version 4")).click()
                ada.waitForURL({ url: String -> url == base + page })
                assertThat(ada.locator("#before del")).hasText("1000")
                assertThat(ada.locator("#after ins")).hasText("500")
                ada.locator("#comment-text").fill("Why 500 and not 750?")
                ada.locator("#comment").click()
                assertThat(ada.locator("#timeline li[data-what=commented]")).hasCount(1)

                // Rae finds it among what they asked, answers, and may not vote on their own change.
                val rae = browser.signedIn(base, "rae", "engineers")
                assertThat(rae.locator("#mine li")).containsText("large-transfer, version 4")
                rae.navigate(base + page)
                assertThat(rae.locator("#timeline")).containsText("Why 500 and not 750?")
                assertThat(rae.locator("#vote-form")).isHidden()
                rae.locator("#comment-text").fill("500 is where the fraud team's cases start")
                rae.locator("#comment").click()
                assertThat(rae.locator("#timeline li[data-what=commented]")).hasCount(2)

                // Both approve: Ada with a word, then Bob, and approval is given.
                ada.reload()
                ada.locator("#vote-text").fill("Fair enough")
                ada.locator("#approve").click()
                assertThat(ada.locator("#standing")).containsText("1 of 2")
                assertThat(ada.locator("#vote-form")).isHidden()
                val bob = browser.signedIn(base, "bob", "risk", then = page)
                bob.locator("#approve").click()
                assertThat(bob.locator("#state")).hasText("Approved, not yet applied")

                // The owning service applies the content approved, and the page says so.
                node.calling("checks", "services").outcome(applied, In2(view.id, AppliedBody(view.hash))).shouldBeOk()
                rae.reload()
                assertThat(rae.locator("#state")).hasText("Applied")
                assertThat(rae.locator("#acting")).isHidden()
                assertThat(rae.locator("#before")).containsText("when amount is at least 1000")
                assertThat(rae.locator("#after")).containsText("when amount is at least 500")
                assertThat(rae.locator("#about")).containsText("31 would have been")
                rae.locator("#timeline li").all().map { it.getAttribute("data-what") } shouldContainExactly listOf(
                    "requested", "awaiting-approval", "commented", "commented", "approved", "approved", "approval-given", "applied",
                )
                assertThat(rae.locator("#timeline")).containsText("Fair enough")
                assertThat(rae.locator("#timeline")).containsText("500 is where the fraud team's cases start")

                // Nothing is waiting on Bob now, and a page asked for while signed out sends you to sign in.
                bob.navigate("$base/")
                assertThat(bob.locator("#waiting li")).hasCount(0)
                assertThat(bob.locator("#recent li")).containsText("Applied")
                val stranger = browser.newContext().newPage()
                stranger.navigate(base + page)
                stranger.locator("#subject").isVisible() shouldBe true
            }
        }.shouldBeRight()
    }
}
