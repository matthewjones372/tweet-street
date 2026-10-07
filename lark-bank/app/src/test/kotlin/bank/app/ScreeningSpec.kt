package bank.app

import bank.domain.AccountId
import bank.domain.Step
import bank.domain.TransferId
import bank.domain.TransferStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A check as spec 0018 describes it, and no more: `POST /screen` declines any transfer of [declineFrom] or more by
 * `large-transfer` version 3, and answers a transfer it has decided before with that first decision. [hold] keeps
 * every request waiting until it is counted down.
 */
private class StubCheck(
    private val declineFrom: BigDecimal,
    private val hold: CountDownLatch = CountDownLatch(0),
    private val delayMillis: Long = 0,
) : AutoCloseable {
    private val json = ObjectMapper()
    private val decided = ConcurrentHashMap<String, String>()
    val asked = ConcurrentHashMap<String, AtomicInteger>()

    /** The `traceparent` each transfer's screening arrived with (bank spec 0024), "" for none. */
    val traceparents = ConcurrentHashMap<String, String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newVirtualThreadPerTaskExecutor()
        createContext("/screen") { exchange ->
            val proposed = json.readTree(exchange.requestBody)
            val transfer = proposed["transfer"].asText()
            asked.computeIfAbsent(transfer) { AtomicInteger() }.incrementAndGet()
            traceparents[transfer] = exchange.requestHeaders.getFirst("traceparent").orEmpty()
            hold.await()
            Thread.sleep(delayMillis)
            val amount = BigDecimal(proposed["amount"]["value"].asText())
            val decision = decided.computeIfAbsent(transfer) {
                if (amount >= declineFrom) """{"outcome":"declined","rule":"large-transfer","version":3,"evidence":"amount >= $declineFrom"}"""
                else """{"outcome":"approved","evidence":"no rule held"}"""
            }
            val body = decision.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun config(whenUnanswered: String = "approve", timeout: String = "300ms") = """
        bank.screening.enabled = true
        bank.screening.url = "$url"
        bank.screening.timeout = $timeout
        bank.screening.whenUnanswered = $whenUnanswered
    """.trimIndent()

    override fun close() = server.stop(0)
}

/** Bank spec 0018: every transfer screened before its source is debited. */
class ScreeningSpec {
    private val run = UUID.randomUUID().toString().take(8)
    private val from = AccountId("scr-a-$run")
    private val to = AccountId("scr-b-$run")

    private fun TestNode.opened() {
        bank.open(from, "Ada", gbp(100_000), "open:$from").shouldBeRight()
        bank.open(to, "Bob", gbp(0), "open:$to").shouldBeRight()
    }

    private fun TestNode.settled(transfer: TransferId) =
        eventually { bank.transferStatus(transfer).getOrNull()?.status in setOf(TransferStatus.Completed, TransferStatus.Rejected) }

    @Test
    fun `an approved transfer moves, and a declined one is rejected with the rule as its reason and moves nothing`() {
        StubCheck(declineFrom = BigDecimal("500.00")).use { check ->
            TestCluster(1, extra = check.config()).running { (node) ->
                node.opened()
                val small = TransferId("scr-small-$run")
                val large = TransferId("scr-large-$run")
                node.bank.transfer(small, from, to, gbp(10_000))
                node.bank.transfer(large, from, to, gbp(60_000))
                node.settled(small) shouldBe true
                node.settled(large) shouldBe true

                node.bank.transferStatus(small).getOrNull()?.status shouldBe TransferStatus.Completed
                val declined = node.bank.transferStatus(large).shouldBeRight()
                declined.status shouldBe TransferStatus.Rejected
                declined.reason shouldBe "Declined by large-transfer, version 3"
                node.bank.balance(from).shouldBeRight().balance shouldBe gbp(90_000)
                node.bank.balance(to).shouldBeRight().balance shouldBe gbp(10_000)
                check.asked.keys shouldBe setOf(small.value, large.value)
            }
        }
    }

    @Test
    fun `two hundred transfers screened at once each get the check's answer, not the policy's`() {
        StubCheck(declineFrom = BigDecimal("500.00"), delayMillis = 50).use { check ->
            val screener = PelicanScreener(check.url, 5.seconds)
            val calls = (1..200).map { n ->
                val step = Step.Screen(from, to, gbp(1_000), requestedAt = 1)
                java.util.concurrent.CompletableFuture.supplyAsync(
                    { screener.screen(TransferId("scr-many-$run-$n"), step) },
                    Executors.newVirtualThreadPerTaskExecutor(),
                )
            }
            val answers = calls.map { it.get(30, TimeUnit.SECONDS) }
            answers.filter { it.isLeft() } shouldBe emptyList()
        }
    }

    @Test
    fun `a check that does not answer in time leaves it to the bank's policy`() {
        val never = CountDownLatch(1)
        StubCheck(declineFrom = BigDecimal("500.00"), hold = never).use { check ->
            TestCluster(1, extra = check.config(whenUnanswered = "decline")).running { (node) ->
                node.opened()
                val transfer = TransferId("scr-slow-$run")
                node.bank.transfer(transfer, from, to, gbp(1_000))
                node.settled(transfer) shouldBe true
                val view = node.bank.transferStatus(transfer).shouldBeRight()
                view.status shouldBe TransferStatus.Rejected
                view.reason shouldBe "Declined: the check did not answer in time"
                node.bank.balance(from).shouldBeRight().balance shouldBe gbp(100_000)
            }
            never.countDown()
        }
    }

    @Test
    fun `approving when unanswered, a transfer moves though the check is down`() {
        val down = StubCheck(declineFrom = BigDecimal.ZERO).apply { close() }
        TestCluster(1, extra = down.config(whenUnanswered = "approve")).running { (node) ->
            node.opened()
            val transfer = TransferId("scr-down-$run")
            node.bank.transfer(transfer, from, to, gbp(1_000))
            node.settled(transfer) shouldBe true
            node.bank.transferStatus(transfer).shouldBeRight().status shouldBe TransferStatus.Completed
        }
    }

    @Test
    fun `a saga that stops mid-screening asks again when it starts, and gets the same decision`() {
        val database = TestPostgres.fresh()
        val released = CountDownLatch(1)
        StubCheck(declineFrom = BigDecimal("500.00"), hold = released).use { check ->
            val transfer = TransferId("scr-moved-$run")
            // Long enough that the node is gone before the saga's own timer decides.
            TestCluster(1, database = database, extra = check.config(timeout = "30s")).running { (node) ->
                node.opened()
                Thread.ofVirtual().start { node.bank.transfer(transfer, from, to, gbp(60_000)) }
                eventually { check.asked[transfer.value] != null } shouldBe true
            }
            released.countDown()
            TestCluster(1, database = database, extra = check.config(timeout = "30s")).running { (node) ->
                // Asked about, the saga starts again on this node, and screens again.
                node.bank.transferStatus(transfer).shouldBeRight()
                node.settled(transfer) shouldBe true
                val view = node.bank.transferStatus(transfer).shouldBeRight()
                view.status shouldBe TransferStatus.Rejected
                view.reason shouldBe "Declined by large-transfer, version 3"
                // Once before the stop, and again after; the sweeper may nudge it into asking a third time, as safely.
                check.asked.getValue(transfer.value).get() shouldBeGreaterThanOrEqual 2
                node.bank.balance(from).shouldBeRight().balance shouldBe gbp(100_000)
            }
        }
    }

    @Test
    fun `a transfer's screening reaches the check inside the transfer's trace`() {
        StubCheck(declineFrom = BigDecimal("500.00")).use { check ->
            TestCluster(1, extra = check.config()).running { (node) ->
                node.opened()
                val trace = UUID.randomUUID().toString().replace("-", "")
                val transfer = "scr-traced-$run"
                val status = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("${node.server.baseUrl}/transfers/$transfer"))
                        .header("Authorization", "Bearer ${TestIdentity.token("Ada")}")
                        .header("Content-Type", "application/json")
                        .header("traceparent", "00-$trace-00f067aa0ba902b7-01")
                        .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(
                            """{"from":"${from.value}","to":"${to.value}","amount":{"value":"1.00","currency":"GBP"}}""",
                        ))
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding(),
                ).statusCode()
                status shouldBe 200
                eventually { check.traceparents[transfer] != null } shouldBe true

                val sent = check.traceparents.getValue(transfer).split("-")
                sent[1] shouldBe trace
                // Sampled, as the caller's was, and the bank's own client span as its parent, not the caller's.
                sent[3] shouldBe "01"
                (sent[2] != "00f067aa0ba902b7") shouldBe true
            }
        }
    }
}
