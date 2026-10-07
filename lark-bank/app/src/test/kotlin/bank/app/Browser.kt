package bank.app

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import java.nio.file.Paths
import com.microsoft.playwright.Playwright

/**
 * A page in headless Chromium, for the length of [block]. The browsers are the ones installed under
 * PLAYWRIGHT_BROWSERS_PATH, and none is downloaded here.
 */
fun <A> inBrowser(block: (Page) -> A): A {
    val env = buildMap {
        put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
        System.getenv("PLAYWRIGHT_BROWSERS_PATH")?.let { put("PLAYWRIGHT_BROWSERS_PATH", it) }
    }
    return Playwright.create(Playwright.CreateOptions().setEnv(env)).use { playwright ->
        // An installed Chromium, where this machine's browsers are not the build this Playwright expects.
        val options = BrowserType.LaunchOptions()
        System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
        playwright.chromium().launch(options).use { browser ->
            browser.newContext().use { context -> block(context.newPage()) }
        }
    }
}

/**
 * Signs in to the bank at [base] as [subject], through the test issuer's form, and lands on [then] (bank spec 0021):
 * the page sends someone not signed in to `/login`, which sends them to the issuer and back.
 */
fun Page.signIn(base: String, subject: String, groups: String = "", then: String = "/") {
    navigate(base + then)
    locator("#subject").fill(subject)
    locator("#groups").fill(groups)
    locator("#sign-in").click()
    // A predicate, not a pattern: a URL pattern reads the `?` of `account.html?id=…` as a wildcard.
    waitForURL({ url: String -> url == base + then })
}
