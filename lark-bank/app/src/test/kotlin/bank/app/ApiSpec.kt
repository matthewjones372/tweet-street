package bank.app

import bank.api.Amount
import bank.api.InsufficientFunds
import bank.api.InvalidAmount
import bank.api.Movement
import bank.api.OpenAccount
import bank.api.TransferRequest
import bank.api.deposit
import bank.api.getAccount
import bank.api.getTransfer
import bank.api.listCurrencies
import bank.api.ledger
import bank.api.openAccount
import bank.api.statement
import bank.api.transfer
import bank.api.withdraw
import bank.domain.AccountError
import bank.domain.TransferError
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** The bank through its HTTP contract, on one node: every call names an endpoint, and every refusal is a declared one. */
class ApiSpec {

    @Test
    fun `an account is opened, paid into, refused, and paid out of over HTTP`() {
        TestCluster(1).running { (node) ->
            apiClient(node.server.baseUrl, JacksonCodecs).use { anyone ->
                val client = anyone.calling("ada")
                val bob = anyone.calling("bob")
                val ops = anyone.calling("olga", "ops")
                client.outcome(openAccount, In2("ada", OpenAccount("GBP", "10.00"))).shouldBeOk().balance shouldBe pounds("10.00")
                bob.outcome(openAccount, In2("bob", OpenAccount("GBP"))).shouldBeOk()

                client.outcome(openAccount, In2("ada", OpenAccount("GBP", "10.00"))).shouldBeOk().balance shouldBe pounds("10.00")
                val clash = anyone.calling("eve").outcome(openAccount, In2("ada", OpenAccount("GBP", "10.00")))
                (clash as Outcome.Err).error.shouldBeInstanceOf<AccountError.AlreadyOpen>()

                client.outcome(deposit, In2("ada", Movement(pounds("5"), "pay-1"))).shouldBeOk().balance shouldBe pounds("15.00")
                client.outcome(deposit, In2("ada", Movement(pounds("5"), "pay-1"))).shouldBeOk().balance shouldBe pounds("15.00")

                val short = bob.outcome(withdraw, In2("bob", Movement(pounds("0.01"), "w-1")))
                (short as Outcome.Err).error shouldBe
                    InsufficientFunds("bob", pounds("0.00"), pounds("0.01"), "Account bob has 0.00 GBP, not 0.01 GBP")

                val missing = client.outcome(getAccount, "nobody")
                (missing as Outcome.Err).error shouldBe AccountError.NoSuchAccount("nobody")

                val moved = client.outcome(transfer, In2("t-1", TransferRequest("ada", "bob", pounds("7.00")))).shouldBeOk()
                moved.status shouldBe "Completed"
                moved.amount shouldBe pounds("7.00")
                client.outcome(transfer, In2("t-1", TransferRequest("ada", "bob", pounds("7.00")))).shouldBeOk().status shouldBe
                    "Completed"
                val reused = client.outcome(transfer, In2("t-1", TransferRequest("ada", "bob", pounds("0.01"))))
                (reused as Outcome.Err).error shouldBe TransferError.TransferIdReused("t-1")
                (client.outcome(getTransfer, "t-404") as Outcome.Err).error shouldBe TransferError.NoSuchTransfer("t-404")

                client.outcome(getAccount, "ada").shouldBeOk().balance shouldBe pounds("8.00")
                bob.outcome(getAccount, "bob").shouldBeOk().balance shouldBe pounds("7.00")

                eventually { ops.call(ledger, Unit).let { it.conserved && it.eventsProjected >= 5 } } shouldBe true
                client.call(statement, In3("ada", 10, null)).map { it.kind } shouldBe listOf("debited", "deposited", "opened")
                // The next page is the lines before the last one seen.
                client.call(statement, In3("ada", 10, 3L)).map { it.kind } shouldBe listOf("deposited", "opened")

                // OpenMetrics, a byte stream to Pelican's client: read as text, as Prometheus does.
                val scraped = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("${node.server.baseUrl}/metrics")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(),
                ).body()
                scraped shouldContain "http_server_requests_total"
                scraped shouldContain "path=\"/transfers/{transferId}\""
                scraped shouldContain "bank_account_commands_total"
                scraped shouldContain "bank_projection_events_total"
            }
        }
    }

    @Test
    fun `an account holds one currency, to its places, and its books balance apart from every other's`() {
        TestCluster(1).running { (node) ->
            apiClient(node.server.baseUrl, JacksonCodecs).use { anyone ->
                val client = anyone.calling("ada")
                client.call(listCurrencies, Unit).map { it.code } shouldBe listOf("BTC", "ETH", "EUR", "GBP", "JPY", "USD")
                client.outcome(openAccount, In2("wallet", OpenAccount("BTC", "0.5"))).shouldBeOk().balance shouldBe
                    Amount("0.50000000", "BTC")
                client.outcome(openAccount, In2("purse", OpenAccount("GBP", "1.00"))).shouldBeOk()

                val tooFine = client.outcome(deposit, In2("wallet", Movement(Amount("0.000000001", "BTC"), "d-1")))
                (tooFine as Outcome.Err).error.shouldBeInstanceOf<InvalidAmount>()
                val unknown = client.outcome(deposit, In2("wallet", Movement(Amount("1", "XAU"), "d-2")))
                (unknown as Outcome.Err).error shouldBe InvalidAmount("The bank keeps no XAU")
                val inPounds = client.outcome(deposit, In2("wallet", Movement(pounds("1.00"), "d-3")))
                (inPounds as Outcome.Err).error.shouldBeInstanceOf<InvalidAmount>().accountCurrency shouldBe "BTC"

                client.outcome(deposit, In2("wallet", Movement(Amount("0.00000001", "BTC"), "d-4"))).shouldBeOk().balance shouldBe
                    Amount("0.50000001", "BTC")
                // In the source's currency, to an account in another: the destination refuses it, and the source is refunded.
                val across = client.outcome(transfer, In2("t-x", TransferRequest("purse", "wallet", pounds("0.50")))).shouldBeOk()
                across.amount shouldBe pounds("0.50")
                eventually { client.outcome(getTransfer, "t-x").shouldBeOk().status == "Refunded" } shouldBe true
                client.outcome(getAccount, "purse").shouldBeOk().balance shouldBe pounds("1.00")

                val books = mapOf("BTC" to "0.50000001", "GBP" to "1.00")
                eventually { anyone.calling("olga", "ops").call(ledger, Unit).let { it.conserved && it.currencies.associate { c -> c.currency to c.balances } == books } } shouldBe
                    true
            }
        }
    }
}
