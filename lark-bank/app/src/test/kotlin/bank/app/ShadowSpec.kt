package bank.app

import bank.access.fga.Fga
import bank.access.fga.Tuple
import bank.api.getAccount
import bank.api.statement
import bank.domain.AccountId
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** bank-access for the test JVM (spec 0022): OpenFGA in memory, the model from bank-access-client's own jar. */
object TestAccess {
    const val KEY = "bank-key-test"
    private val server by lazy {
        GenericContainer("openfga/openfga:v1.21.0").withCommand("run")
            .withEnv(mapOf("OPENFGA_AUTHN_METHOD" to "preshared", "OPENFGA_AUTHN_PRESHARED_KEYS" to KEY, "OPENFGA_PLAYGROUND_ENABLED" to "false"))
            .withExposedPorts(8080).waitingFor(Wait.forHttp("/healthz").forPort(8080)).apply { start() }
    }
    val url: String get() = "http://${server.host}:${server.getMappedPort(8080)}"

    /** A store of its own, named [store], with the model bank-access-client was built against. */
    fun store(store: String): Fga {
        val model = requireNotNull(javaClass.classLoader.getResourceAsStream("bank-access/model.json")) { "no model in bank-access-client" }
            .use { String(it.readAllBytes()) }
        Fga.create(url, KEY, store, model)
        return Fga(url, KEY, store)
    }

    fun config(store: String) = """
        bank.accessShadow.enabled = true
        bank.accessShadow.url = "$url"
        bank.accessShadow.token = "$KEY"
        bank.accessShadow.store = "$store"
        bank.accessShadow.recheckAfter = 1s
    """.trimIndent()
}

/** Bank spec 0022's `access-shadow`: bank-access asked every question the bank's own rules answer, and compared. */
class ShadowSpec {
    private val run = UUID.randomUUID().toString().take(8)
    private val http = HttpClient.newHttpClient()

    /** A counter on [base]'s /metrics, summed over the lines whose labels contain each of [labels]. */
    private fun counted(base: String, name: String, vararg labels: String): Double =
        http.send(HttpRequest.newBuilder(URI.create("$base/metrics")).build(), HttpResponse.BodyHandlers.ofString()).body()
            .lines().filter { it.startsWith(name) && labels.all(it::contains) }
            .sumOf { it.substringAfterLast(' ').toDouble() }

    private fun eventually(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + 30_000_000_000
        while (!done()) {
            check(System.nanoTime() < until) { "never: $what" }
            Thread.sleep(200)
        }
    }

    @Test
    fun `the bank answers as before, bank-access agrees with it, and a deliberately wrong relationship is counted`() {
        val store = "shadow-$run"
        val fga = TestAccess.store(store)
        val acc = "acc-$run"
        TestCluster(1, extra = TestAccess.config(store)).running { (node) ->
            val base = node.server.baseUrl
            node.bank.open(AccountId(acc), "ada", gbp(5_000), "open:$acc").shouldBeRight()
            // What access-sync writes from the Opened, written here instead.
            fga.write(listOf(Tuple("person:ada", "owner", "account:$acc"), Tuple("bank:lark", "bank", "account:$acc")))

            apiClient(base, JacksonCodecs).use { client ->
                client.calling("ada").response(getAccount, acc).status shouldBe 200
                client.calling("ada").response(statement, In3(acc, 50, null)).status shouldBe 200
                client.calling("eve").response(getAccount, acc).status shouldBe 404
                eventually("three questions agreed on") { counted(base, "bank_access_shadow_total", "agreed") >= 3.0 }
                counted(base, "bank_access_disagreed_total") shouldBe 0.0

                // A relationship that is wrong: Eve as acc's owner. The bank still answers from its own rules.
                fga.write(listOf(Tuple("person:eve", "owner", "account:$acc")))
                client.calling("eve").response(getAccount, acc).status shouldBe 404
                eventually("the disagreement counted") { counted(base, "bank_access_disagreed_total", "view-account") == 1.0 }
            }
        }
    }
}
