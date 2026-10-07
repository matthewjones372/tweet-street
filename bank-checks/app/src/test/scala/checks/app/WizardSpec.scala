package checks.app

import bank.issuer.TestIssuer
import checks.access.adapters.OidcConfig
import checks.access.service.AccessConfig
import checks.monitoring.adapters.KafkaConfig
import checks.bankevents.AccountEventsConfig
import checks.platform.TestPostgres
import checks.policy.service.PolicyConfig
import com.microsoft.playwright.{Page, Playwright}
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import zio.*
import zio.http.{Body, Client, Request, Server, URL}
import zio.test.*

import scala.jdk.CollectionConverters.*

object WizardSpec extends ZIOSpecDefault:
  // The bank's test identity provider: its sign-in page believes whoever you say you are (lark-bank spec 0021).
  private val issuer = ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attempt(TestIssuer(0, "127.0.0.1", null, null))))

  // A port known up front: the provider sends people back to the callback it was told.
  private def settings =
    for
      issuer   <- ZIO.service[TestIssuer]
      database <- TestPostgres.fresh
      port     <- ZIO.attempt(scala.util.Using.resource(java.net.ServerSocket(0))(_.getLocalPort))
    yield Settings(
      port = port,
      database = database,
      policy = PolicyConfig(1.hour),
      kafka = KafkaSettings(false, KafkaConfig("", "", 1), AccountEventsConfig("", "", "")),
      access = AccessConfig(Set("risk", "admins"), 1.hour, 10.minutes),
      identity = OidcConfig(issuer.getUrl, "checks-pages", "", s"http://127.0.0.1:$port/callback")
    )

  // Through the provider's own page, as a person signs in: who they are, and their groups.
  private def signIn(base: String, page: Page, subject: String, groups: String): Unit =
    page.navigate(base)
    page.fill("#subject", subject)
    page.fill("#groups", groups)
    page.click("#sign-in")

  private def screen(base: String, transfer: String, amount: String, to: String = "b") =
    val json =
      s"""{"transfer":"$transfer","from":"a","to":"$to","amount":{"value":"$amount","currency":"GBP"},"requestedAtMillis":1700000000000}"""
    ZIO.scoped(
      Client
        .batched(Request.post(URL.decode(s"$base/screen").toOption.get, Body.fromString(json)))
        .flatMap(_.body.asString)
    )

  // Headless Nix Chromium, as lark-bank's page tests drive it.
  private def inBrowser[A](block: Page => A): Task[A] = ZIO.attemptBlocking {
    val env = Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1") ++ sys.env
      .get("PLAYWRIGHT_BROWSERS_PATH")
      .map("PLAYWRIGHT_BROWSERS_PATH" -> _)
    val playwright = Playwright.create(Playwright.CreateOptions().setEnv(env.asJava))
    try
      val browser = playwright.chromium().launch()
      try block(browser.newContext().newPage())
      finally browser.close()
    finally playwright.close()
  }

  private def writeTheRule(base: String)(page: Page): (String, String, String, String) =
    val console = scala.collection.mutable.ListBuffer[String]()
    page.onConsoleMessage(message => console += message.text())
    try steps(base, page)
    catch
      case failed: Exception =>
        throw RuntimeException(
          s"${failed.getMessage.take(200)}\nthe page said: ${page.innerText("body")}\nconsole: $console"
        )

  private def steps(base: String, page: Page): (String, String, String, String) =
    signIn(base, page, "ada", "risk")
    page.click("#new-rule")

    // 1. What it is about.
    page.fill("#rule-name", "large-unknown")
    page.selectOption("#rule-does", "transfer")
    page.selectOption("#rule-severity", "high")
    page.click("#next")

    // 2. The conditions: amount at least 1000, and none of (currency BTC, or to a trusted account).
    page.selectOption("#g-0-field", "amount")
    page.selectOption("#g-0-comparison", "gte")
    page.fill("#g-0-value", "1000")
    page.click("#g-add-group")
    page.selectOption("#g-1-mode", "none")
    page.selectOption("#g-1-0-field", "currency")
    page.selectOption("#g-1-0-comparison", "eq")
    page.fill("#g-1-0-value", "BTC")
    page.click("#g-1-add-condition")
    page.selectOption("#g-1-1-field", "to")
    page.selectOption("#g-1-1-comparison", "eq")
    page.fill("#g-1-1-value", "trusted")
    page.click("#next")

    // 3. Read it back.
    assertThat(page.locator("#words")).isVisible()
    val words = page.textContent("#words")
    page.click("#next")

    // 4. Try it, on one transfer and over the last seven days.
    page.fill("#try-from", "a")
    page.fill("#try-to", "b")
    page.fill("#try-currency", "GBP")
    page.fill("#try-amount", "2500")
    page.fill("#try-hourOfDay", "10")
    page.click("#try")
    assertThat(page.locator("#tried")).containsText("It holds.")
    val tried = page.textContent("#tried")
    page.click("#dry-run-button")
    assertThat(page.locator("#dry-run")).containsText("would have declined")
    val dry = page.textContent("#dry-run")
    page.click("#next")

    // 5. Make it live.
    page.click("#make-live")
    assertThat(page.locator("#stored")).containsText("version 1, live")
    val stored = page.textContent("#stored")

    // Test a transfer: one the rule declines, and one to the trusted account it lets through.
    page.navigate(s"$base/#/test")
    page.fill("#test-from", "a")
    page.fill("#test-to", "b")
    page.fill("#test-amount", "5000")
    page.click("#test-button")
    assertThat(page.locator("#test-outcome")).containsText("Declined by large-unknown, version 1.")
    assertThat(page.locator("#test-rules")).containsText("yes: declines")
    page.fill("#test-to", "trusted")
    page.click("#test-button")
    assertThat(page.locator("#test-outcome")).containsText("Approved")

    // Signed out, the wizard says so, and signs in again through the provider when asked.
    page.click("#sign-out")
    assertThat(page.locator("#signed-out")).isVisible()
    page.click("#sign-in-button")
    page.fill("#subject", "ada")
    page.fill("#groups", "risk")
    page.click("#sign-in")
    assertThat(page.locator("#who")).containsText("ada")
    (words, tried, dry, stored)

  private def outsider(base: String)(page: Page): (String, String) =
    signIn(base, page, "eve", "marketing")
    (page.innerText("body"), page.url())

  def spec = suite("The wizard")(
    test("an admin writes a rule of two groups with a negation, reads it, tries it, and makes it live") {
      for
        settings <- settings
        _        <- Migrations.run(settings.database)
        result   <- (for
                    port    <- Server.install(Main.routes)
                    base     = s"http://127.0.0.1:$port"
                    _       <- ZIO.foreachDiscard(1 to 10)(n => screen(base, s"before-$n", s"${n * 300}.00"))
                    seen    <- inBrowser(writeTheRule(base))
                    answer  <- screen(base, "after", "5000.00").repeatUntil(_.contains("declined")).timeout(5.seconds)
                    trusted <- screen(base, "after-trusted", "5000.00", to = "trusted")
                  yield (seen, answer, trusted)).provide(Main.layers(settings), Client.default)
        ((words, tried, dry, stored), answer, trusted) = result
      yield assertTrue(
        words == "amount is at least 1000 and not (currency is \"BTC\" or to is \"trusted\")",
        tried.contains("amount (2500) >= 1000"),
        dry.contains("declined 7 of 10"),
        stored.contains("version 1, live"),
        answer.exists(_.contains("\"rule\":\"large-unknown\"")),
        trusted.contains("approved")
      )
    } @@ TestAspect.withLiveClock,
    test("someone the provider puts in none of the admins' groups is refused, and given no session") {
      for
        settings       <- settings
        _              <- Migrations.run(settings.database)
        (said, landed) <- (for
                            port <- Server.install(Main.routes)
                            seen <- inBrowser(outsider(s"http://127.0.0.1:$port"))
                          yield seen).provide(Main.layers(settings))
      yield assertTrue(said.contains("eve is in no group that may change the policy"), landed.contains("/callback"))
    } @@ TestAspect.withLiveClock
  ).provideShared(issuer.orDie)
