package bank.app

import bank.api.OpenAccount
import bank.api.TransferRequest
import bank.api.openAccount
import bank.api.transfer
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * Bank spec 0021's specification of who may do what: every endpoint, as every kind of caller, and the status each must
 * answer. Someone else's account is missing, not forbidden; an ops view refused is forbidden, since it names nothing.
 */
class CallerTableSpec {

    /** Who calls: the token they send, or none. */
    private enum class Who(val token: () -> String?) {
        Nobody({ null }),
        Owner({ TestIdentity.token("ada") }),
        Stranger({ TestIdentity.token("eve") }),
        Support({ TestIdentity.token("sam", "support") }),
        SupportWithGrant({ TestIdentity.token("gil", "support") }),
        Auditor({ TestIdentity.token("aud", "auditor") }),
        Ops({ TestIdentity.token("olga", "ops") }),
        ActingAsOwner({ TestIdentity.token("ada", actor = "bob") }),
    }

    /** One endpoint as a request that can be sent again, and what each caller must get. */
    private class Row(
        val method: String,
        val template: String,
        val path: (Who) -> String,
        val body: ((Who) -> String)? = null,
        val expected: Map<Who, Int>,
    )

    private fun statuses(
        nobody: Int, owner: Int, stranger: Int, support: Int, granted: Int, auditor: Int, ops: Int, acting: Int,
    ) = mapOf(
        Who.Nobody to nobody, Who.Owner to owner, Who.Stranger to stranger, Who.Support to support, Who.SupportWithGrant to granted,
        Who.Auditor to auditor, Who.Ops to ops, Who.ActingAsOwner to acting,
    )

    private fun unique() = UUID.randomUUID().toString()

    private val gbp = """"amount":{"value":"0.01","currency":"GBP"}"""

    private val table = listOf(
        Row("PUT", "/accounts/{accountId}", { "/accounts/new-${unique()}" }, { """{"currency":"GBP","initial":"0"}""" },
            statuses(401, 201, 201, 201, 201, 201, 201, 403)),
        Row("GET", "/accounts/{accountId}", { "/accounts/a1" }, null, statuses(401, 200, 404, 404, 200, 200, 404, 200)),
        Row("POST", "/accounts/{accountId}/deposits", { "/accounts/a1/deposits" }, { """{$gbp,"reference":"${unique()}"}""" },
            statuses(401, 200, 404, 404, 404, 404, 404, 403)),
        Row("POST", "/accounts/{accountId}/withdrawals", { "/accounts/a1/withdrawals" }, { """{$gbp,"reference":"${unique()}"}""" },
            statuses(401, 200, 404, 404, 404, 404, 404, 403)),
        Row("GET", "/accounts/{accountId}/statement", { "/accounts/a1/statement" }, null, statuses(401, 200, 404, 404, 200, 200, 404, 200)),
        Row("GET", "/accounts/{accountId}/looks", { "/accounts/a1/looks" }, null, statuses(401, 200, 404, 404, 200, 200, 404, 200)),
        Row("PUT", "/transfers/{transferId}", { "/transfers/${unique()}" }, { """{"from":"a1","to":"b1",$gbp}""" },
            statuses(401, 200, 404, 404, 404, 404, 404, 403)),
        Row("GET", "/transfers/{transferId}", { "/transfers/t1" }, null, statuses(401, 200, 404, 404, 200, 200, 200, 200)),
        Row("GET", "/transfers", { "/transfers" }, null, statuses(401, 403, 403, 403, 403, 200, 200, 403)),
        Row("POST", "/payrolls/{payrollId}", { "/payrolls/${unique()}" }, { """{"credits":[{"account":"b1",$gbp}]}""" },
            statuses(401, 403, 403, 403, 403, 403, 202, 403)),
        Row("GET", "/currencies", { "/currencies" }, null, statuses(401, 200, 200, 200, 200, 200, 200, 200)),
        Row("GET", "/ledger", { "/ledger" }, null, statuses(401, 403, 403, 403, 403, 200, 200, 403)),
        Row("GET", "/cluster", { "/cluster" }, null, statuses(401, 403, 403, 403, 403, 200, 200, 403)),
        Row("GET", "/ops/stream", { "/ops/stream" }, null, statuses(401, 403, 403, 403, 403, 200, 200, 403)),
        // Allowed for an auditor and ops, and unanswered here: this cluster has no bank-access to ask (bank spec 0022).
        Row("GET", "/access/accounts/{accountId}/viewers", { "/access/accounts/a1/viewers" }, null,
            statuses(401, 403, 403, 403, 403, 503, 503, 403)),
        Row("GET", "/access/people/{person}", { "/access/people/ada" }, null, statuses(401, 403, 403, 403, 403, 503, 503, 403)),
        Row("GET", "/accounts", { "/accounts" }, null, statuses(401, 200, 200, 200, 200, 200, 200, 200)),
        Row("GET", "/me", { "/me" }, null, statuses(401, 200, 200, 200, 200, 200, 200, 200)),
        // Acting is a signed-in page's: a token, whoever it names, has no session to swap.
        Row("POST", "/act-as/{subject}", { "/act-as/ada" }, null, statuses(401, 403, 403, 403, 403, 403, 403, 403)),
        Row("DELETE", "/act-as", { "/act-as" }, null, statuses(401, 403, 403, 403, 403, 403, 403, 403)),
        // The open four: a probe, a scrape and the document ask no one who they are.
        Row("GET", "/health", { "/health" }, null, statuses(200, 200, 200, 200, 200, 200, 200, 200)),
        Row("GET", "/ready", { "/ready" }, null, statuses(200, 200, 200, 200, 200, 200, 200, 200)),
        Row("GET", "/metrics", { "/metrics" }, null, statuses(200, 200, 200, 200, 200, 200, 200, 200)),
        Row("GET", "/openapi.json", { "/openapi.json" }, null, statuses(200, 200, 200, 200, 200, 200, 200, 200)),
    )

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    /** The status alone: an event stream is closed as soon as its status has arrived. */
    private fun status(base: String, row: Row, who: Who): Int {
        val request = HttpRequest.newBuilder(URI.create(base + row.path(who)))
            .method(row.method, row.body?.let { HttpRequest.BodyPublishers.ofString(it(who)) } ?: HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/json")
        who.token()?.let { request.header("Authorization", "Bearer $it") }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().close()
        return response.statusCode()
    }

    @Test
    fun `every endpoint answers every kind of caller as the table says`() {
        TestCluster(1).running { (node) ->
            val base = node.server.baseUrl
            apiClient(base, JacksonCodecs).use { anyone ->
                anyone.calling("ada").outcome(openAccount, In2("a1", OpenAccount("GBP", "100.00"))).shouldBeOk()
                anyone.calling("bob").outcome(openAccount, In2("b1", OpenAccount("GBP"))).shouldBeOk()
                anyone.calling("ada").outcome(transfer, In2("t1", TransferRequest("a1", "b1", pounds("1.00")))).shouldBeOk()
            }
            // As Approvals would have it given: gil in support may see a1, and nothing else, for the next hour.
            node.grants.give("table-grant", "gil", Access.View("a1"), java.time.Instant.now().plusSeconds(3600))
            // And bob may act as ada, which the acting column's token says he is doing.
            node.grants.give("table-acting", "bob", Access.ActAs("ada"), java.time.Instant.now().plusSeconds(3600))
            assertSoftly {
                table.forEach { row ->
                    Who.entries.forEach { who ->
                        withClue("${row.method} ${row.template} as $who") {
                            status(base, row, who) shouldBe row.expected.getValue(who)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `and the table covers every endpoint the document describes`() {
        TestCluster(1).running { (node) ->
            val document = http.send(
                HttpRequest.newBuilder(URI.create("${node.server.baseUrl}/openapi.json")).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
            val described = Regex(""""(/[^"]*)":\s*\{\s*"(get|put|post|delete|patch)"""").findAll(document)
                .map { it.groupValues[1] }.toSet()
            val tabled = table.map { it.template }.toSet()

            // Found, not assumed: an empty match would pass the check below and prove nothing.
            (described.size >= 15) shouldBe true
            ("/accounts/{accountId}" in described) shouldBe true
            withClue("endpoints described but not in the table") { (described - tabled) shouldBe emptySet() }
        }
    }
}
