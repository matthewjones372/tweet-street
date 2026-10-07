package bank.api

import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.afterStatus
import java.time.Instant
import java.util.concurrent.Executors

// Every look by staff is recorded (bank spec 0021): each request answered for someone in a staff group, or while
// someone is acting as a customer, and every request refused, whoever made it.

/** One request recorded: who, acting as whom, which endpoint and account, the answer, from where, and the grant used. */
data class Look(
    val at: Instant,
    val who: Caller,
    val endpoint: String,
    val account: String?,
    val status: Int,
    val address: String?,
    val userAgent: String?,
    val grant: String?,
)

/** A look as the log holds it, numbered. */
data class Logged(val id: Long, val look: Look)

/** Where looks are kept. The app keeps them in Postgres and publishes them; a test may keep none. */
interface AccessLog {
    fun record(look: Look)

    /** Every look at [account], newest first. */
    fun looksAt(account: String, limit: Int = 200): List<Logged>

    companion object {
        val none: AccessLog = object : AccessLog {
            override fun record(look: Look) = Unit

            override fun looksAt(account: String, limit: Int): List<Logged> = emptyList()
        }
    }
}

/** The answers that refuse: a stranger's account is missing, an ops view forbidden. */
private val REFUSALS = setOf(403, 404)

// Written beside the request rather than in it: a staff look answers as quickly as anyone's.
private val writes = Executors.newVirtualThreadPerTaskExecutor()

/** Records each request that [shouldRecord] picks, with the grant [Grants] says allowed it. */
fun recording(log: AccessLog, grants: Grants): Filter = afterStatus { params, status, _ ->
    val who = if (caller in params) params[caller] else null
    if (who != null && (who.isStaff || who.isImpersonating || status in REFUSALS)) {
        val endpoint = checkNotNull(params.endpoint)
        val account = if (accountId in params) params[accountId] else null
        val look = Look(
            at = Instant.now(),
            who = who,
            endpoint = "${endpoint.method.name} ${endpoint.pathSpec.template}",
            account = account,
            status = status,
            address = params.header("X-Forwarded-For")?.substringBefore(',')?.trim(),
            userAgent = params.header("User-Agent"),
            grant = grants.grantUsed(who, account),
        )
        writes.execute { log.record(look) }
    }
}

/** A request header, from the backend's own request: these are recorded, not inputs any endpoint declares. */
private fun Params.header(name: String): String? =
    (underlying as? org.apache.pekko.http.javadsl.model.HttpRequest)?.getHeader(name)?.orElse(null)?.value()

/** What a customer is told of each look: the role and what it was, never the name. */
fun Look.role(): String = when {
    who.isImpersonating -> "Support, acting as you"
    "auditor" in who.groups -> "Auditor"
    "support" in who.groups -> "Support"
    "admins" in who.groups -> "Admin"
    "ops" in who.groups -> "Ops"
    else -> "Customer"
}

/** The endpoint as a customer would say it. */
fun Look.what(): String = when (endpoint) {
    "GET /accounts/{accountId}" -> "viewed the account"
    "GET /accounts/{accountId}/statement" -> "viewed the statement"
    "GET /accounts/{accountId}/looks" -> "viewed who has looked"
    "GET /access/accounts/{accountId}/viewers" -> "viewed who can see the account"
    else -> endpoint
}
