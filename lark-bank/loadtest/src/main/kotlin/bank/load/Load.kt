package bank.load

import java.io.IOException
import bank.api.Amount
import bank.api.InsufficientFunds
import bank.api.Movement
import bank.api.OpenAccount
import bank.api.Payroll
import bank.api.PayrollCredit
import bank.api.TransferRequest
import bank.api.deposit
import bank.api.ledger
import bank.api.openAccount
import bank.api.payroll
import bank.api.transfer
import bank.api.transferCounts
import bank.api.withdraw
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.signedInAs
import bank.issuer.TestIssuer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.StepScope
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.warmingUp
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a run is told, from the Job's environment. */
data class Plan(
    val scenario: String,
    val baseUrl: String,
    val rate: Int,
    val over: Duration,
    val accounts: Int,
    val hotAccounts: Int,
    val reports: Path,
    /** The test issuer the bank trusts outside home (bank spec 0021), which hands the load its token. */
    val issuer: String,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()) = Plan(
            scenario = env["SCENARIO"] ?: "transfers",
            baseUrl = env["BASE_URL"] ?: "http://127.0.0.1:8080",
            rate = env["RATE"]?.toInt() ?: 500,
            over = (env["SECONDS"]?.toLong() ?: 60).seconds,
            accounts = env["ACCOUNTS"]?.toInt() ?: 10_000,
            hotAccounts = env["HOT_ACCOUNTS"]?.toInt() ?: 1,
            reports = Path.of(env["REPORT_DIR"] ?: "build/reports/proofload"),
            issuer = env["ISSUER_URL"] ?: "http://127.0.0.1:9000",
        )
    }
}

private val move = step("deposit or withdraw")
private val pay = step("pay the merchant")
private val send = step("transfer")
private val bulk = step("payroll of 100")

/** A refusal the bank declared is the bank working; anything else — a 503, a timeout, a 500 — is a failure. */
private fun StepScope.expect(outcome: Outcome<*, *>, declared: (Any?) -> Boolean) {
    if (outcome is Outcome.Err && !declared(outcome.error)) fail("undeclared or unavailable: ${outcome.error}")
}

private fun account(plan: Plan) = "acc-${ThreadLocalRandom.current().nextInt(plan.accounts)}"

// Two currencies, so every run checks that each balances on its own (bank spec 0011): even accounts are in pounds,
// odd ones in bitcoin, and a transfer is always between two of the same.
private data class Held(val code: String, val exponent: Int, val opening: String, val most: Long)

private val pounds = Held("GBP", 2, "10000.00", 200)
private val bitcoin = Held("BTC", 8, "10", 200_000)

private fun heldBy(account: String) = if (account.substringAfterLast('-').toInt() % 2 == 0) pounds else bitcoin

/** A random amount of [held]'s currency, from its smallest unit up to [Held.most] of them. */
private fun amountOf(held: Held, most: Long = held.most) =
    Amount(BigDecimal.valueOf(ThreadLocalRandom.current().nextLong(1, most + 1), held.exponent).toPlainString(), held.code)

/** Another account in [account]'s currency, or null where the draw gave the same one. */
private fun partnerOf(plan: Plan, account: String): String? {
    val draw = ThreadLocalRandom.current().nextInt(plan.accounts / 2) * 2 + account.substringAfterLast('-').toInt() % 2
    return "acc-$draw".takeIf { it != account && draw < plan.accounts }
}

fun scenarioFor(plan: Plan, client: ApiClient): Scenario = when (plan.scenario) {
    // Many accounts, evenly: every node's shards busy, and the journal written from all of them at once.
    "spread" -> scenario("spread") {
        exec(move) { step ->
            val random = ThreadLocalRandom.current()
            val account = account(plan)
            val body = Movement(amountOf(heldBy(account)), UUID.randomUUID().toString())
            val outcome = if (random.nextBoolean()) client.outcome(deposit, In2(account, body))
            else client.outcome(withdraw, In2(account, body))
            step.expect(outcome) { it is InsufficientFunds }
        }
    }
    // Everything at a handful of accounts: each is one actor, so this is the single-writer ceiling.
    "hot" -> scenario("hot") {
        exec(pay) { step ->
            val merchant = "merchant-${ThreadLocalRandom.current().nextInt(plan.hotAccounts)}"
            step.expect(client.outcome(deposit, In2(merchant, Movement(Amount("0.01", "GBP"), UUID.randomUUID().toString())))) { false }
        }
    }
    // Sagas between accounts on different nodes; "chaos" is the same load while pods are deleted under it.
    "transfers", "chaos" -> scenario(plan.scenario) {
        exec(send) { step ->
            val from = account(plan)
            val to = partnerOf(plan, from) ?: return@exec
            val outcome = client.outcome(transfer, In2(UUID.randomUUID().toString(), TransferRequest(from, to, amountOf(heldBy(from)))))
            step.expect(outcome) { false }
        }
    }
    // Credits sent in bulk through a reliable producer: many writes per request, confirmed rather than answered.
    "payroll" -> scenario("payroll") {
        exec(bulk) { step ->
            val credits = (1..100).map { account(plan).let { PayrollCredit(it, amountOf(heldBy(it), most = 1)) } }
            step.expect(client.outcome(payroll, In2(UUID.randomUUID().toString(), Payroll(credits)))) { false }
        }
    }
    else -> error("no scenario called ${plan.scenario}: spread, hot, transfers, chaos or payroll")
}

/** Every account the scenario draws from, opened before the clock starts. Opening one twice is a retry, so a rerun is safe. */
fun open(plan: Plan, client: ApiClient) {
    val names = (0 until plan.accounts).map { "acc-$it" } + (0 until plan.hotAccounts).map { "merchant-$it" }
    val room = Semaphore(256)
    Executors.newVirtualThreadPerTaskExecutor().use { threads ->
        names.forEach { name ->
            room.acquire()
            threads.submit {
                try {
                    val held = if (name.startsWith("merchant")) pounds else heldBy(name)
                    client.outcome(openAccount, In2(name, OpenAccount(held.code, held.opening)))
                } finally {
                    room.release()
                }
            }
        }
    }
    println("opened ${names.size} accounts")
}

/** Waits for every transfer to settle and the read models to catch up, and answers whether the books balanced. */
fun settles(client: ApiClient, within: Duration = 180.seconds): Boolean {
    val deadline = System.nanoTime() + within.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
        try {
            val counts = client.call(transferCounts, Unit)
            val books = client.call(ledger, Unit)
            println("pending ${counts.pending}, completed ${counts.completed}, conserved ${books.conserved}, events ${books.eventsProjected}")
            books.currencies.forEach { println("  ${it.currency}: ${it.accounts} accounts, balances ${it.balances}, in flight ${it.inFlight}, conserved ${it.conserved}") }
            if (counts.pending == 0L && books.conserved) return true
        } catch (unreachable: IOException) {
            // The node asked may be restarting after chaos; the books are checked again, not given up on.
            println("the bank did not answer ($unreachable); asking again")
        }
        Thread.sleep(2_000)
    }
    return false
}

/** A token from the test issuer's test grant: nothing but that issuer hands one out this way. */
fun tokenFrom(issuer: String, subject: String, groups: List<String>): String {
    val form = "grant_type=${TestIssuer.TEST_GRANT}&subject=$subject&groups=${groups.joinToString(",")}"
    val request = HttpRequest.newBuilder(URI.create("$issuer/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()
    val answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    check(answer.statusCode() == 200) { "the test issuer at $issuer answered ${answer.statusCode()}: ${answer.body()}" }
    return answer.body().substringAfter("\"access_token\":\"").substringBefore('"')
}

fun summary(result: RunResult): String = buildString {
    appendLine("requests ${result.count}, ok ${result.ok}, failed ${result.failed}")
    result.steps.values.forEach { stats ->
        val time = stats.responseTime
        appendLine("  ${stats.name}: ${stats.count} — p50 ${time.p50}, p95 ${time.p95}, p99 ${time.p99}, max ${time.max}")
    }
}

fun main() {
    val plan = Plan.fromEnvironment()
    println("$plan")
    val passed = apiClient(plan.baseUrl, JacksonCodecs).use { anyone ->
        // One caller owns every account the load opens, and is in ops to read the ledger and send payrolls.
        val client = anyone.signedInAs(tokenFrom(plan.issuer, subject = "load", groups = listOf("ops")))
        open(plan, client)
        val load = scenarioFor(plan, client).at(plan.rate.perSecond, over = plan.over).warmingUp(10.seconds)
        val result = Proofload().run(load)
        Files.createDirectories(plan.reports)
        result.writeHtmlReport(plan.reports.resolve("${plan.scenario}.html"))
        println(summary(result))
        val balanced = settles(client)
        println(if (balanced) "LEDGER CONSERVED" else "LEDGER DID NOT BALANCE")
        balanced && result.failed * 1_000 <= result.count
    }
    // Long enough for load.sh to copy the report out of the pod before it goes.
    System.getenv("HOLD_SECONDS")?.toLong()?.let { Thread.sleep(it * 1_000) }
    exitProcess(if (passed) 0 else 1)
}

