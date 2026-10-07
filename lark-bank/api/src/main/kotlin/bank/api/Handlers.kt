package bank.api

import io.github.matthewjones372.pelican.pekko.streamedNow
import java.util.concurrent.Executors
import java.time.Duration
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.NotUsed
import org.apache.pekko.util.ByteString
import arrow.core.Either
import arrow.core.flatMap
import bank.domain.AccountError
import bank.domain.AccountId
import bank.domain.Balance
import bank.domain.Bank
import bank.domain.Currencies
import bank.domain.Money
import bank.domain.TransferError
import bank.domain.TransferId
import bank.domain.Unavailable
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.arrow.toOutcome
import io.github.matthewjones372.pelican.pages
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.metrics.metrics
import io.github.matthewjones372.pelican.metrics.otel.openTelemetry
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.pekko.bytesNow
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.request
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.propagation.TextMapGetter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * What the read models answer. The app answers from Postgres; a test from a list. What a customer's request reads is
 * read as [Caller] (bank spec 0021): the database returns only what they may see, whatever the query asks.
 */
interface ReadModels {
    fun statement(who: Caller, account: String, limit: Int, before: Long? = null): List<StatementLine>
    fun ledger(): Ledger
    fun transfers(): TransferCounts

    /** Every account [who] holds, or is acting for, from the balances read model. */
    fun accountsOf(who: Caller): List<AccountView>
}

/** Sends each credit of a payroll reliably; the amounts are already read, in their currencies. */
fun interface Payrolls {
    fun send(id: String, credits: List<Pair<AccountId, Money>>): Either<Busy, PayrollAccepted>
}

/**
 * How people sign in to the pages, and what verifies a caller: a bearer token, or a signed-in session. The app builds
 * it from pelican-oidc against the bank's identity provider; a test from its own issuer.
 */
interface Signing {
    val endpoints: List<io.github.matthewjones372.pelican.ServerEndpoint>
    val authenticator: io.github.matthewjones372.pelican.Authenticator
    val guard: io.github.matthewjones372.pelican.PageGuard

    /** The `Set-Cookie` that makes the session in [cookies] act as [subject] until [until]; null with no session. */
    fun actAs(cookies: String?, subject: String, until: java.time.Instant): String?

    /** The `Set-Cookie` that ends acting in the session in [cookies]; null with no session. */
    fun stopActing(cookies: String?): String?
}

/** While acting as someone, nothing changes: what support may do is look (bank spec 0021). */
private val readOnly = Forbidden("Acting as a customer is read-only: nothing is moved or changed while acting")

private val opsThreads = Executors.newVirtualThreadPerTaskExecutor()

fun bankApi(
    bank: Bank,
    currencies: Currencies,
    reads: ReadModels,
    payrolls: Payrolls,
    clusterView: () -> ClusterView,
    healthy: () -> Healthy,
    scrape: () -> String,
    registry: MeterRegistry,
    ops: () -> OpsSnapshot,
    signIn: Signing,
    owners: Owners = Owners(bank),
    rules: Rules = Rules(),
    looks: AccessLog = AccessLog.none,
    grants: Grants = Grants.none,
    audit: Audit = Audit.none,
    telemetry: OpenTelemetry = OpenTelemetry.noop(),
) = api(
    endpoints = listOf(
        openAccount handledOrFail { (id, body) ->
            val who = this[caller]
            if (!rules.canOpen(who)) return@handledOrFail forbidden(Forbidden("An account is opened by its owner, not by someone acting as them"))
            currencies.amount(Amount(body.initial, body.currency)).fold({ invalidAmount(it) }) { initial ->
                bank.open(AccountId(id), who.actingAs, initial, reference = "open:$id").onRight { owners.remember(it) }
                    .map(::view).toOutcome { refusal ->
                    when (refusal) {
                        is AccountError.AlreadyOpen -> accountExists(refusal)
                        is AccountError.InvalidAmount -> invalidAmount(InvalidAmount(refusal.message))
                        is Unavailable -> unavailable(refusal)
                        is AccountError.NoSuchAccount, is AccountError.InsufficientFunds, is AccountError.CurrencyMismatch ->
                            error("opening refused as $refusal")
                    }
                }
            }
        },
        getAccount handledOrFail { id ->
            bank.balance(AccountId(id)).flatMap { visible(rules, this[caller], it) }.map(::view).toOutcome { refusal ->
                when (refusal) {
                    is AccountError.NoSuchAccount -> accountMissing(refusal)
                    is Unavailable -> unavailable(refusal)
                    is AccountError.AlreadyOpen, is AccountError.InsufficientFunds, is AccountError.InvalidAmount,
                    is AccountError.CurrencyMismatch,
                    -> error("a balance refused as $refusal")
                }
            }
        },
        deposit handledOrFail { (id, body) ->
            val who = this[caller]
            if (who.isImpersonating) return@handledOrFail forbidden(readOnly)
            currencies.amount(body.amount).fold({ invalidAmount(it) }) { amount ->
                owners.check(who, id, rules::canMove).flatMap { bank.deposit(AccountId(id), amount, body.reference) }.map(::view)
                    .toOutcome { refusal ->
                        when (refusal) {
                            is AccountError.NoSuchAccount -> accountMissing(refusal)
                            is AccountError.InvalidAmount -> invalidAmount(InvalidAmount(refusal.message))
                            is AccountError.CurrencyMismatch -> invalidAmount(InvalidAmount(refusal.message, refusal.account))
                            is Unavailable -> unavailable(refusal)
                            is AccountError.AlreadyOpen, is AccountError.InsufficientFunds -> error("a deposit refused as $refusal")
                        }
                    }
            }
        },
        withdraw handledOrFail { (id, body) ->
            val who = this[caller]
            if (who.isImpersonating) return@handledOrFail forbidden(readOnly)
            currencies.amount(body.amount).fold({ invalidAmount(it) }) { amount ->
                owners.check(who, id, rules::canMove).flatMap { bank.withdraw(AccountId(id), amount, body.reference) }.map(::view)
                    .toOutcome { refusal ->
                        when (refusal) {
                            is AccountError.NoSuchAccount -> accountMissing(refusal)
                            is AccountError.InsufficientFunds -> insufficientFunds(short(refusal))
                            is AccountError.InvalidAmount -> invalidAmount(InvalidAmount(refusal.message))
                            is AccountError.CurrencyMismatch -> invalidAmount(InvalidAmount(refusal.message, refusal.account))
                            is Unavailable -> unavailable(refusal)
                            is AccountError.AlreadyOpen -> error("a withdrawal refused as $refusal")
                        }
                    }
            }
        },
        transfer handledOrFail { (id, body) ->
            val who = this[caller]
            if (who.isImpersonating) return@handledOrFail forbidden(readOnly)
            currencies.amount(body.amount).fold({ invalidTransfer(InvalidTransfer(it.message)) }) { amount ->
                when (val from = owners.check(who, body.from, rules::canMove)) {
                    is Either.Left -> when (val refusal = from.value) {
                        is Unavailable -> unavailable(refusal)
                        else -> accountMissing(AccountError.NoSuchAccount(body.from))
                    }
                    is Either.Right ->
                        bank.transfer(TransferId(id), AccountId(body.from), AccountId(body.to), amount).map(::view).toOutcome { refusal ->
                            when (refusal) {
                                is TransferError.TransferIdReused -> transferReused(refusal)
                                is TransferError.SameAccount -> invalidTransfer(InvalidTransfer(refusal.message))
                                is TransferError.InvalidTransferAmount -> invalidTransfer(InvalidTransfer(refusal.message))
                                is Unavailable -> unavailable(refusal)
                                is TransferError.NoSuchTransfer -> error("starting a transfer refused as $refusal")
                            }
                        }
                }
            }
        },
        getTransfer handledOrFail { id ->
            val who = this[caller]
            bank.transferStatus(TransferId(id))
                .flatMap { moving ->
                    val from = moving.from to owners.of(moving.from).getOrNull()
                    val to = moving.to to owners.of(moving.to).getOrNull()
                    if (rules.canViewTransfer(who, from, to)) Either.Right(moving) else Either.Left(TransferError.NoSuchTransfer(id))
                }
                .map(::view)
                .toOutcome { refusal ->
                    when (refusal) {
                        is TransferError.NoSuchTransfer -> transferMissing(refusal)
                        is Unavailable -> unavailable(refusal)
                        is TransferError.TransferIdReused, is TransferError.SameAccount,
                        is TransferError.InvalidTransferAmount,
                        -> error("a transfer's status refused as $refusal")
                    }
                }
        },
        payroll handledOrFail { (id, body) ->
            if (!rules.canSendPayroll(this[caller])) return@handledOrFail forbidden(Forbidden("A payroll is run by ops"))
            val credits = body.credits.map { credit -> currencies.amount(credit.amount).map { AccountId(credit.account) to it } }
            val unreadable = credits.firstNotNullOfOrNull { it.leftOrNull() }
            if (unreadable != null) invalidAmount(unreadable) else payrolls.send(id, credits.mapNotNull { it.getOrNull() }).toOutcome(busy)
        },
        statement handledOrFail { (id, max, older) ->
            owners.check(this[caller], id, rules::canView).fold(
                { refusal -> if (refusal is Unavailable) unavailable(refusal) else accountMissing(AccountError.NoSuchAccount(id)) },
                { ok(reads.statement(this[caller], id, max, older)) },
            )
        },
        accountLooks handledOrFail { id ->
            val who = this[caller]
            owners.check(who, id, rules::canView).fold(
                { refusal -> if (refusal is Unavailable) unavailable(refusal) else accountMissing(AccountError.NoSuchAccount(id)) },
                {
                    val auditing = "auditor" in who.groups && !who.isImpersonating
                    ok(
                        looks.looksAt(id)
                            .filter { (_, look) -> auditing || (look.status in 200..299 && (look.who.isImpersonating || look.who.subject != who.actingAs)) }
                            .map { (_, look) ->
                                LookView(
                                    look.at.toEpochMilli(), look.role(), look.what(), look.status,
                                    who = look.who.actor.takeIf { auditing } ?: look.who.subject.takeIf { auditing },
                                    actingAs = look.who.subject.takeIf { auditing && look.who.isImpersonating },
                                    grant = look.grant.takeIf { auditing },
                                )
                            },
                    )
                },
            )
        },
        accountViewers handledOrFail { id -> audited(rules, this[caller]) { audit.viewers(id) } },
        personSees handledOrFail { subject -> audited(rules, this[caller]) { audit.sees(subject) } },
        myAccounts handledNow { this[caller].let { who -> if (rules.mayActAs(who)) reads.accountsOf(who) else emptyList() } },
        me handledNow {
            this[caller].let { CallerView(it.subject, it.name, it.groups.sorted(), it.actor, it.actingUntil?.toEpochMilli()) }
        },
        actAs handledOrFail { (subject, held) ->
            val until = rules.actingUntil(this[caller], subject)
                ?: return@handledOrFail forbidden(Forbidden("No grant to act as $subject: ask for one in Approvals"))
            val swapped = signIn.actAs(held, subject, until)
                ?: return@handledOrFail forbidden(Forbidden("Acting as a customer is for a signed-in page, not a token"))
            setHeader(setCookie, swapped)
            ok(ActingView(subject, until.toEpochMilli()))
        },
        stopActing handledOrFail { held ->
            val who = this[caller]
            val ended = signIn.stopActing(held)
                ?: return@handledOrFail forbidden(Forbidden("Acting as a customer is for a signed-in page, not a token"))
            setHeader(setCookie, ended)
            ok(ActingView(who.subject, System.currentTimeMillis()))
        },
        transferCounts handledOrFail { opsOnly(rules, this[caller]) { reads.transfers() } },
        ledger handledOrFail { opsOnly(rules, this[caller]) { reads.ledger() } },
        listCurrencies handledNow {
            currencies.all.map { CurrencyView(it.currency.code, it.currency.exponent, it.symbol, it.kind.name.lowercase()) }
        },
        cluster handledOrFail { opsOnly(rules, this[caller]) { clusterView() } },
        health handledNow { healthy() },
        ready handledOrFail {
            healthy().let { now -> if (now.ready) ok(now) else notReady(NotReady(now.failing)) }
        },
        metrics bytesNow { Source.single(ByteString.fromString(scrape())) },
        // One snapshot a second, each built on a virtual thread: it asks the read models, which block.
        opsStream streamedNow {
            if (!rules.canSeeOps(this[caller])) throw ApiException(403, "The ops view is for ops")
            Source.tick(Duration.ZERO, Duration.ofSeconds(1), Unit)
                .mapAsync(1) { CompletableFuture.supplyAsync(ops, opsThreads) }
                .mapMaterializedValue { NotUsed.getInstance() }
        },
    ) + signIn.endpoints,
    codecs = JacksonCodecs,
) {
    title = "Lark Bank"
    version = "0.1.0"
    // The customer and ops pages (bank specs 0006 and 0007) beside the endpoints, which always win (Pelican 0059).
    // Signed in, or sent to sign in first (bank spec 0021): the pages are someone's, and never cached.
    pages = pages("ui").guardedBy(signIn.guard)
    authenticate(bearer, signIn.authenticator)
    // A server span per request, continuing the caller's trace (bank spec 0024); outermost, so a refusal is in it too.
    filter(openTelemetry(telemetry, incomingHeaders = pekkoHeaders))
    filter(metrics(registry))
    filter(completedInTrace)
    filter(recording(looks, grants))
}

/** An inbound `traceparent`, read off Pekko's own request: the one header Pelican's filter cannot read for itself. */
private val pekkoHeaders: TextMapGetter<Params> = object : TextMapGetter<Params> {
    override fun keys(carrier: Params): Iterable<String> = carrier.request.headers.map { it.lowercaseName() }

    override fun get(carrier: Params?, key: String): String? = carrier?.request?.getHeader(key)?.map { it.value() }?.orElse(null)
}

// ---- amounts, in and out (bank spec 0011) ----

/** An amount the caller wrote, read in a currency the bank keeps: refused rather than rounded if it has too many places. */
fun Currencies.amount(amount: Amount): Either<InvalidAmount, Money> =
    of(amount.currency).mapLeft { InvalidAmount(it.message) }
        .flatMap { currency -> Money.parse(amount.value, currency).mapLeft { InvalidAmount(it.message) } }

/** Every place the currency has: "12.50", "0.00125000". */
fun Money.toAmount() = Amount(amount.toPlainString(), currency.code)

/** The account if the caller may see it: someone else's is missing, exactly as one that does not exist. */
private fun visible(rules: Rules, who: Caller, account: Balance): Either<AccountError, Balance> =
    if (rules.canView(who, account.id, account.owner)) Either.Right(account) else Either.Left(AccountError.NoSuchAccount(account.id))

/**
 * Who owns each account, asked of the account once and then remembered on this node: an owner is set when the account
 * opens and never changes, so the check before every move need not ask again. Without it, each transfer asked its
 * source account twice, and a transfer's p50 rose by a fifth.
 */
class Owners(private val bank: Bank, private val most: Int = 200_000) {
    private val known = ConcurrentHashMap<String, String>()

    fun of(id: String): Either<AccountError, String> =
        known[id]?.let { Either.Right(it) } ?: bank.balance(AccountId(id)).map { it.owner }.onRight { owner ->
            // Crude, and enough: a node that has seen this many accounts starts again rather than growing.
            if (known.size >= most) known.clear()
            known[id] = owner
        }

    /** The owner, as the account answered it when it opened: the node that opened it never asks. */
    fun remember(opened: Balance) {
        if (known.size >= most) known.clear()
        known[opened.id] = opened.owner
    }

    /** Someone else's account is missing, exactly as one that does not exist. */
    fun check(who: Caller, id: String, may: (Caller, String, String) -> Boolean): Either<AccountError, String> =
        of(id).flatMap { owner -> if (may(who, id, owner)) Either.Right(owner) else Either.Left(AccountError.NoSuchAccount(id)) }
}

private fun <T : Any> opsOnly(rules: Rules, who: Caller, answer: () -> T): Outcome<Forbidden, T> =
    if (rules.canSeeOps(who)) ok(answer()) else forbidden(Forbidden("This view is for ops"))

/** An auditor's question, for those who may ask it; no answer from bank-access is a 503, never an empty list. */
private fun audited(rules: Rules, who: Caller, answer: () -> List<Because>?) =
    if (!rules.canAudit(who)) forbidden(Forbidden("Who can see what is for ops, admins and auditors"))
    else answer()?.let { ok(it) } ?: unavailable(Unavailable("bank-access did not answer; ask again"))

fun view(balance: Balance) = AccountView(balance.id, balance.owner, balance.balance.toAmount())

private fun view(transfer: bank.domain.TransferView) =
    TransferView(transfer.id, transfer.from, transfer.to, transfer.amount.toAmount(), transfer.status.name, transfer.reason)

private fun short(refused: AccountError.InsufficientFunds) =
    InsufficientFunds(refused.id, refused.balance.toAmount(), refused.requested.toAmount(), refused.message)
