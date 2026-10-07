package bank.api

import kotlin.time.Duration.Companion.seconds
import bank.domain.AccountError
import bank.domain.TransferError
import bank.domain.Unavailable
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.responseHeader
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.text

// ---- inputs ----

val accountId = pathParam<String>("accountId", description = "The account's id, chosen by whoever opens it")
val transferId = pathParam<String>("transferId", description = "The transfer's id: the same id twice is one transfer")
val payrollId = pathParam<String>("payrollId", description = "The payroll's id: each credit's reference derives from it")
val limit = queryParam<Int>("limit", description = "How many to return").default(50)

/** The next page of a statement: the lines before this sequence number. */
val before = queryParam<Long>("before", description = "Only lines before this sequence number: the next page").optional()

/**
 * An amount as the API writes it (bank spec 0011): a plain decimal string with no more places than its currency has,
 * "12.50" GBP or "0.00125000" BTC. Never a JSON number, which a client could read as a float.
 */
data class Amount(val value: String, val currency: String)

/** An account in [currency], which it keeps, with [initial] paid in as it opens. Its owner is whoever opens it. */
data class OpenAccount(val currency: String, val initial: String = "0")

/** An amount, and the caller's reference for it: the same reference twice moves the money once. */
data class Movement(val amount: Amount, val reference: String)

data class TransferRequest(val from: String, val to: String, val amount: Amount)

data class PayrollCredit(val account: String, val amount: Amount)

data class Payroll(val credits: List<PayrollCredit>)

// ---- outputs ----

data class AccountView(val id: String, val owner: String, val balance: Amount)

/** A currency the bank keeps, and how to show it: [places] of minor units, `fiat` or `crypto`. */
data class CurrencyView(val code: String, val places: Int, val symbol: String, val kind: String)

data class StatementLine(
    val sequence: Long,
    val kind: String,
    val amount: Amount,
    val balance: Amount,
    val reference: String,
    val atMillis: Long,
)

/** Where a transfer has got to. */
data class TransferView(
    val id: String,
    val from: String,
    val to: String,
    val amount: Amount,
    val status: String,
    val reason: String?,
)

/**
 * The bank's books as the read models see them, one currency at a time. `conserved` is the claim the whole design
 * exists for: in every currency, every balance plus the money in flight between accounts is exactly what was paid
 * in less what was paid out. Currencies never net against each other.
 */
data class Ledger(
    val accounts: Long,
    val eventsProjected: Long,
    val conserved: Boolean,
    val currencies: List<Books>,
)

data class Books(
    val currency: String,
    val accounts: Long,
    val balances: String,
    val inFlight: String,
    val paidIn: String,
    val paidOut: String,
    val conserved: Boolean,
)

data class TransferCounts(val pending: Long, val completed: Long, val rejected: Long, val refunded: Long)

data class PayrollAccepted(val payroll: String, val sent: Int)

data class Member(val node: String, val status: String, val upNumber: Int)

data class ClusterView(val self: String, val leader: String?, val members: List<Member>, val unreachable: List<String>, val readModelsOn: String?)

data class Healthy(val ready: Boolean, val failing: List<String>)

/** Who the bank takes the caller to be; [actingFor] is set while someone is acting as them. */
data class CallerView(
    val subject: String,
    val name: String,
    val groups: List<String>,
    val actor: String?,
    /** When acting as [subject] ends, in epoch milliseconds; null when nobody is acting. */
    val actingUntilMillis: Long? = null,
)

/** Acting as [subject] (bank spec 0021), until [untilMillis]: what a session swapped for theirs says. */
data class ActingView(val subject: String, val untilMillis: Long)

// ---- the live ops view (bank spec 0007) ----

/** One node as it last said of itself; [ageMillis] is how long ago, so a node that has gone quiet shows it. */
data class OpsNode(
    val node: String,
    val status: String,
    val ageMillis: Long,
    val shards: Map<String, Long>,
    val entities: Map<String, Long>,
    val asksPerSecond: Double,
    val askP99Millis: Double,
    val commandsPerSecond: Map<String, Double>,
)

/** One journal database: its newest ordering, and how many events behind it each read model is. */
data class OpsJournal(val database: String, val head: Long, val lag: Map<String, Long>)

/** The whole bank, once a second: every node's own snapshot, and what one node can answer for all of them. */
data class OpsSnapshot(
    val atMillis: Long,
    val servedBy: String,
    val leader: String?,
    val readModelsOn: String?,
    val unreachable: List<String>,
    val nodes: List<OpsNode>,
    val ledger: Ledger,
    val transfers: TransferCounts,
    val journal: List<OpsJournal>,
)

// ---- failures ----

/** A caller the bank knows, asking for something their groups do not allow: ops views, a payroll. */
data class Forbidden(val message: String)

val forbidden = errorJson<Forbidden>(403, "The caller's groups do not allow this")

val accountMissing = errorJson<AccountError.NoSuchAccount>(404, "No account with that id")
val accountExists = errorJson<AccountError.AlreadyOpen>(409, "An account with that id is already open")
/** The account holds less than was asked of it. */
data class InsufficientFunds(val account: String, val balance: Amount, val requested: Amount, val message: String)

/**
 * Not an amount the account can move: not positive, not a plain decimal in its currency's places, no currency the bank
 * keeps, or another currency than the account's, which [accountCurrency] then names. Refused, never converted.
 */
data class InvalidAmount(val message: String, val accountCurrency: String? = null)

val insufficientFunds = errorJson<InsufficientFunds>(409, "The account cannot cover that amount")
val invalidAmount = errorJson<InvalidAmount>(422, "Only a positive amount, in its currency's places and the account's currency, moves")
val unavailable = errorJson<Unavailable>(503, "The bank could not answer in time; try again with the same reference")

val transferMissing = errorJson<TransferError.NoSuchTransfer>(404, "No transfer with that id")
val transferReused = errorJson<TransferError.TransferIdReused>(409, "That transfer id was used for a different transfer")

/** Why a transfer request cannot be a transfer at all: from and to are the same, or the amount is not one. */
data class InvalidTransfer(val message: String)

val invalidTransfer = errorJson<InvalidTransfer>(422, "That is not a transfer the bank can make")

/** The producer's window of unconfirmed credits is full: the bank is behind, and the payroll should be sent again. */
data class Busy(val message: String)

val busy = errorJson<Busy>(503, "Too many credits are unconfirmed; send the payroll again")

// ---- endpoints ----

val openAccount = endpoint(accountId, jsonBody<OpenAccount>()) {
    put("accounts" / accountId)
    authenticatedBy(caller)
    summary = "Open an account, owned by whoever opens it"
    json<AccountView>(status = 201).orFail(accountExists, invalidAmount, forbidden, unavailable)
}

val getAccount = endpoint(accountId) {
    get("accounts" / accountId)
    authenticatedBy(caller)
    summary = "An account's balance, from the account itself; someone else's account is missing, not forbidden"
    json<AccountView>().orFail(accountMissing, unavailable)
}

val deposit = endpoint(accountId, jsonBody<Movement>()) {
    post("accounts" / accountId / "deposits")
    authenticatedBy(caller)
    summary = "Pay money in"
    json<AccountView>().orFail(accountMissing, invalidAmount, forbidden, unavailable)
}

val withdraw = endpoint(accountId, jsonBody<Movement>()) {
    post("accounts" / accountId / "withdrawals")
    authenticatedBy(caller)
    summary = "Take money out"
    json<AccountView>().orFail(accountMissing, insufficientFunds, invalidAmount, forbidden, unavailable)
}

val statement = endpoint(accountId, limit, before) {
    get("accounts" / accountId / "statement")
    authenticatedBy(caller)
    summary = "An account's most recent movements, newest first, from the statements read model"
    json<List<StatementLine>>().orFail(accountMissing, unavailable)
}

val transfer = endpoint(transferId, jsonBody<TransferRequest>()) {
    put("transfers" / transferId)
    authenticatedBy(caller)
    summary = "Move money from one of the caller's accounts; answers once settled, or still pending after a second"
    json<TransferView>().orFail(accountMissing, transferReused, invalidTransfer, forbidden, unavailable)
}

val getTransfer = endpoint(transferId) {
    get("transfers" / transferId)
    authenticatedBy(caller)
    summary = "Where a transfer has got to, for whoever may see either end of it"
    json<TransferView>().orFail(transferMissing, unavailable)
}

val transferCounts = endpoint {
    get("transfers")
    authenticatedBy(caller)
    summary = "How many transfers are in each state, from the sweeper's read model"
    json<TransferCounts>() orFail forbidden
}

val payroll = endpoint(payrollId, jsonBody<Payroll>()) {
    post("payrolls" / payrollId)
    authenticatedBy(caller)
    summary = "Credit many accounts at once, delivered reliably rather than answered"
    json<PayrollAccepted>(status = 202).orFail(busy, invalidAmount, forbidden)
}

val listCurrencies = endpoint {
    get("currencies")
    authenticatedBy(caller)
    summary = "The currencies the bank keeps: an account is opened in one, and holds only that"
    json<List<CurrencyView>>()
}

val ledger = endpoint {
    get("ledger")
    authenticatedBy(caller)
    summary = "Every balance, the money in flight, and whether they add up"
    json<Ledger>() orFail forbidden
}

val cluster = endpoint {
    get("cluster")
    authenticatedBy(caller)
    summary = "The members this node sees, and which one runs the read models"
    json<ClusterView>() orFail forbidden
}

val opsStream = endpoint {
    get("ops" / "stream")
    authenticatedBy(caller)
    summary = "The bank as it is now, once a second: every node, the ledger and the journal"
    sse<OpsSnapshot>(eventName = "snapshot", keepAlive = 5.seconds)
}

val myAccounts = endpoint {
    get("accounts")
    authenticatedBy(caller)
    summary = "The caller's own accounts, from the balances read model"
    json<List<AccountView>>()
}

val me = endpoint {
    get("me")
    authenticatedBy(caller)
    summary = "Who the bank takes the caller to be"
    json<CallerView>()
}

// ---- who has looked (bank spec 0021) ----

/**
 * One look at an account: when, by whom in what role, and what was seen. [who], [actingAs], [grant] and refusals are
 * the auditor's alone; a customer is told the role.
 */
data class LookView(
    val atMillis: Long,
    val role: String,
    val what: String,
    val status: Int,
    val who: String? = null,
    val actingAs: String? = null,
    val grant: String? = null,
)

val accountLooks = endpoint(accountId) {
    get("accounts" / accountId / "looks")
    authenticatedBy(caller)
    summary = "Who has looked at this account: by role for its owner, by name, with refusals, for an auditor"
    json<List<LookView>>().orFail(accountMissing, unavailable)
}

// ---- acting as a customer (bank spec 0021) ----

val customer = pathParam<String>("subject", description = "The customer's subject, as the identity provider names them")

/** The request's cookies, which carry the signed-in session that acting replaces. */
val cookies = headerParam<String>("Cookie", description = "The signed-in page's session").optional()

val setCookie = responseHeader<String>("Set-Cookie")

val actAs = endpoint(customer, cookies) {
    post("act-as" / customer)
    authenticatedBy(caller)
    emits(setCookie)
    summary = "Act as a customer, read-only, while a grant for it lives: a signed-in page's session becomes theirs"
    json<ActingView>().orFail(forbidden)
}

val stopActing = endpoint(cookies) {
    delete("act-as")
    authenticatedBy(caller)
    emits(setCookie)
    summary = "Stop acting as a customer, and be yourself again: answers whom the acting was as, ended now"
    json<ActingView>().orFail(forbidden)
}

// ---- who can see what, and why (bank spec 0022) ----

/**
 * One reason [person] can see [account]: the relationship bank-access holds ([relation]: owner, supporter or auditor),
 * what it [says], and what wrote it: the [event] that opened the account, or the [approval] a grant came from. [account]
 * is null for every account, an auditor's; [untilMillis] is when a grant ends.
 */
data class Because(
    val person: String,
    val account: String?,
    val relation: String,
    val says: String,
    val sinceMillis: Long? = null,
    val untilMillis: Long? = null,
    val approval: String? = null,
    val event: String? = null,
)

val person = pathParam<String>("person", description = "A person's subject, as the identity provider names them")

val accountViewers = endpoint(accountId) {
    get("access" / "accounts" / accountId / "viewers")
    authenticatedBy(caller)
    summary = "Who can see this account today, and why: for ops, admins and auditors"
    json<List<Because>>().orFail(forbidden, unavailable)
}

val personSees = endpoint(person) {
    get("access" / "people" / person)
    authenticatedBy(caller)
    summary = "Which accounts this person can see today, and why: for ops, admins and auditors"
    json<List<Because>>().orFail(forbidden, unavailable)
}

/** What a node that cannot serve yet says: which of its probes are failing. */
data class NotReady(val failing: List<String>)

val notReady = errorJson<NotReady>(503, "This node cannot serve yet")

/** Kubernetes' readiness probe: a 503 keeps the pod out of the Service until its cluster membership is up. */
val ready = endpoint {
    get("ready")
    summary = "200 when this node can serve, 503 when it cannot"
    json<Healthy>() orFail notReady
}

val health = endpoint {
    get("health")
    summary = "Whether this node can serve"
    json<Healthy>()
}

val metrics = endpoint {
    get("metrics")
    // OpenMetrics, the one format that carries exemplars: a latency bucket's trace (bank spec 0024).
    summary = "Every meter, in OpenMetrics, with exemplars"
    bytes(mediaType = "application/openmetrics-text; version=1.0.0; charset=utf-8")
}
