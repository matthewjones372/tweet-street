package bank.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import bank.domain.AccountId
import io.github.matthewjones372.lark.app.use
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.stream.Stream

/** Bank spec 0007: every node's snapshot over one stream, from whichever node serves it. */
class OpsSpec {

    private val json = ObjectMapper()

    /** The snapshots [base] streams, parsed, until [enough] says stop or [seconds] pass. */
    private fun watch(base: String, seconds: Long, enough: (JsonNode) -> Boolean): JsonNode? {
        val request = HttpRequest.newBuilder(URI.create("$base/ops/stream"))
            .header("Authorization", "Bearer ${TestIdentity.token("olga", "ops")}")
            .GET().build()
        val lines: Stream<String> = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofLines()).body()
        val until = System.nanoTime() + seconds * 1_000_000_000
        return lines.use { stream ->
            stream.filter { it.startsWith("data:") }
                .map { json.readTree(it.removePrefix("data:").trim()) }
                .takeWhile { System.nanoTime() < until }
                .filter(enough)
                .findFirst()
                .orElse(null)
        }
    }

    private fun JsonNode.node(name: String): JsonNode? = get("nodes").firstOrNull { it["node"].asText() == name }

    @Test
    fun `one node's stream carries every node's snapshot, and a stopped node's snapshot stops`() {
        val cluster = TestCluster(3)
        cluster.node(0).use { one: TestNode ->
            cluster.node(1).use { _: TestNode ->
                val third = Detached(cluster.node(2))
                third.awaitUp()
                val base = one.server.baseUrl

                val opened = System.nanoTime()
                val all = watch(base, 30) { snapshot -> snapshot["nodes"].size() == 3 }
                val tookMillis = (System.nanoTime() - opened) / 1_000_000
                withClue("the stream from n0 carries n0, n1 and n2") { all.shouldNotBeNull() }
                withClue("all three within two seconds of opening the stream, not $tookMillis ms") {
                    tookMillis shouldBeLessThan 2_000
                }
                all!!["servedBy"].asText() shouldBe "n0"
                all["ledger"]["conserved"].asBoolean() shouldBe true
                all["journal"].size() shouldBe 1
                all.node("n2")!!["ageMillis"].asLong() shouldBeLessThan 3_000
                all["nodes"].sumOf { it["shards"]["account"]?.asInt() ?: 0 } shouldBeGreaterThan 0

                // Asks made on n0 show on n0's card as a rate and a p99.
                val asking = Thread.ofVirtual().start {
                    repeat(200) { one.bank.balance(AccountId("nobody-$it")); Thread.sleep(20) }
                }
                val asked = watch(base, 30) { snapshot -> (snapshot.node("n0")?.get("asksPerSecond")?.asDouble() ?: 0.0) > 0 }
                asking.join()
                withClue("n0's asks show on its card") { asked.shouldNotBeNull() }
                asked!!.node("n0")!!["askP99Millis"].asDouble() shouldBeGreaterThan 0.0

                third.stop()
                // Once n2 is gone its last snapshot ages, while n0's and n1's stay fresh.
                val aged = watch(base, 30) { snapshot -> (snapshot.node("n2")?.get("ageMillis")?.asLong() ?: 0) > 3_000 }
                withClue("n2's snapshot stops once n2 has stopped") { aged.shouldNotBeNull() }
                aged!!.node("n0")!!["ageMillis"].asLong() shouldBeLessThan 3_000
                aged.node("n2")!!["ageMillis"].asLong() shouldBeGreaterThan 3_000L
            }.shouldBeRight()
        }.shouldBeRight()
    }

    @Test
    fun `the ops page shows three nodes and the ledger balanced, and greys a node that stops`() {
        val cluster = TestCluster(3)
        cluster.node(0).use { one: TestNode ->
            cluster.node(1).use { _: TestNode ->
                val third = Detached(cluster.node(2))
                third.awaitUp()
                inBrowser { page ->
                    page.signIn(one.server.baseUrl, "olga", groups = "ops", then = "/ops")
                    page.url() shouldEndWith "/ops"
                    assertThat(page.locator(".node")).hasCount(3, LocatorAssertions.HasCountOptions().setTimeout(15_000.0))
                    assertThat(page.locator("#ledger-state")).hasText("Balanced")
                    assertThat(page.locator(".node[data-node=n0] h3")).containsText("serving this page")

                    third.stop()
                    assertThat(page.locator(".node[data-node=n2]"))
                        .hasAttribute("data-stale", "true", LocatorAssertions.HasAttributeOptions().setTimeout(20_000.0))
                    assertThat(page.locator(".node[data-node=n0]")).hasAttribute("data-stale", "false")
                }
            }.shouldBeRight()
        }.shouldBeRight()
    }

    @Test
    fun `a p99 is the smallest bucket under which 99 in 100 fall`() {
        quantile(mapOf(1.0 to 90.0, 5.0 to 99.0, 50.0 to 100.0), 0.99) shouldBe 5.0
        quantile(mapOf(1.0 to 0.0, 5.0 to 0.0), 0.99) shouldBe 0.0
        quantile(emptyMap(), 0.99) shouldBe 0.0
    }

    @Test
    fun `the books are gauges an alert can watch, and a balanced ledger reads a gap of 0`() {
        TestCluster(1).running { (node) ->
            node.bank.open(AccountId("gauge-1"), "Ada", gbp(1_000), "open:gauge-1").shouldBeRight()
            val metrics = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${node.server.baseUrl}/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
            fun value(series: String) = metrics.lines().first { it.startsWith(series) }.substringAfterLast(' ').toDouble()
            // A gap per currency: pounds, which the account holds, and bitcoin, which nobody does yet.
            value("bank_ledger_gap{currency=\"GBP\"") shouldBe 0.0
            value("bank_ledger_gap{currency=\"BTC\"") shouldBe 0.0
            value("bank_transfers_pending ") shouldBe 0.0
            metrics.lines().filter { it.startsWith("bank_journal_lag{") }.shouldNotBeNull().size shouldBeGreaterThan 0
        }
    }
}
