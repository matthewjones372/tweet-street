package bank.app

import bank.api.DailyLimitExceeded
import bank.api.InvalidAmount
import bank.api.Movement
import bank.api.OpenAccount
import bank.api.TransferRequest
import bank.api.getAccount
import bank.api.openAccount
import bank.api.transfer
import bank.api.withdraw
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Bank spec 0027 over HTTP: an account opened with a daily limit, refused past it, with what is left today. */
class DailyLimitApiSpec {

    @Test
    fun `withdrawals and transfers past an account's daily limit are refused, saying the limit and what is left`() {
        TestCluster(1).running { (node) ->
            apiClient(node.server.baseUrl, JacksonCodecs).use { anyone ->
                val ada = anyone.calling("ada")
                anyone.calling("bob").outcome(openAccount, In2("bob", OpenAccount("GBP"))).shouldBeOk()
                ada.outcome(openAccount, In2("ada", OpenAccount("GBP", "500.00", dailyLimit = "100.00"))).shouldBeOk()

                val paid = ada.outcome(withdraw, In2("ada", Movement(pounds("60.00"), "w-1"))).shouldBeOk()
                paid.balance shouldBe pounds("440.00")
                val refused = ada.outcome(withdraw, In2("ada", Movement(pounds("50.00"), "w-2")))
                (refused as Outcome.Err).error shouldBe DailyLimitExceeded(
                    "ada", pounds("100.00"), pounds("40.00"), pounds("50.00"),
                    "Account ada may pay out 100.00 GBP a day and has 40.00 GBP of that left today, not 50.00 GBP",
                )

                val rejected = ada.outcome(transfer, In2("t-1", TransferRequest("ada", "bob", pounds("40.01"))))
                    .shouldBeOk()
                rejected.status shouldBe "Rejected"
                rejected.reason shouldBe
                    "Account ada may pay out 100.00 GBP a day and has 40.00 GBP of that left today, not 40.01 GBP"

                val moved = ada.outcome(transfer, In2("t-2", TransferRequest("ada", "bob", pounds("40.00"))))
                    .shouldBeOk()
                moved.status shouldBe "Completed"
                ada.outcome(getAccount, "ada").shouldBeOk().balance shouldBe pounds("400.00")
            }
        }
    }

    @Test
    fun `a daily limit that is not an amount of the account's currency is refused at opening`() {
        TestCluster(1).running { (node) ->
            apiClient(node.server.baseUrl, JacksonCodecs).use { anyone ->
                val opened = anyone.calling("ada")
                    .outcome(openAccount, In2("ada", OpenAccount("GBP", "1.00", dailyLimit = "1.005")))
                (opened as Outcome.Err).error shouldBe InvalidAmount("'1.005' is not an amount of GBP")
                anyone.calling("ada").outcome(getAccount, "ada").shouldBeInstanceOf<Outcome.Err<*>>()
            }
        }
    }
}
