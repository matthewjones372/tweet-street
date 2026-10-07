package bank.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import bank.api.Caller
import bank.api.Grants
import bank.events.v1.ApprovalEvent
import bank.events.v1.ApprovalEvent.EventCase
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.kafka.Kafka
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.kafka.consume
import io.github.matthewjones372.lark.kafka.mapRecord
import io.github.matthewjones372.lark.kafka.runCommitting
import io.github.matthewjones372.lark.kafka.within
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.start
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/*
 * Support's grants (bank spec 0021): a person in support asks Approvals to see one account for a while; once someone
 * else approves, the bank hears it on `bank.approval-events`, keeps the grant until it expires, and tells Approvals it
 * applied it. The bank owns what a grant may be, so it checks each one here rather than trusting the request.
 */

/** The kind of request a grant is in Approvals, whose policy names who approves it. */
const val GRANT_KIND = "bank.access-grant"

/** Approvals' topic, which the bank reads and never writes. */
object ApprovalTopic {
    const val NAME = "bank.approval-events"

    /** Every node reads its share of the partitions under this one group. */
    const val GROUP = "lark-bank-access"
}

/** How long a grant lasts when the asker does not say, and the longest it may (spec 0021, settled 3). */
val USUAL_GRANT: Duration = Duration.ofMinutes(30)
val LONGEST_GRANT: Duration = Duration.ofHours(4)

/** What a grant lets its holder do: see one account, or act as one customer, read-only. */
sealed interface Access {
    /** The name the grant's `access` fact and its row's `kind` give it. */
    val kind: String

    data class View(val account: String) : Access {
        override val kind get() = "view"
    }

    data class ActAs(val customer: String) : Access {
        override val kind get() = "act-as"
    }
}

/** One grant as asked: [person] to have [access] for [lasting] from the moment it is approved. */
data class Ask(val person: String, val access: Access, val lasting: Duration) {
    companion object {
        /**
         * The grant [requested] asks for, or why the bank will not give it: a view of one account, or acting as one
         * customer, for the person who asked, who is in support, for [USUAL_GRANT] unless it says otherwise and at most
         * [LONGEST_GRANT].
         */
        fun of(requested: ApprovalEvent.Requested): Either<String, Ask> {
            val facts = requested.factsMap
            val asker = requested.requester
            val written = facts["for"]
            val lasting = if (written == null) USUAL_GRANT else Either.catch { Duration.parse(written) }.getOrNull()
            val access = when (facts["access"]) {
                "view" -> facts["account"]?.takeIf(String::isNotBlank)?.let(Access::View)
                    ?: return "a grant to view names the account it is for".left()
                "act-as" -> facts["customer"]?.takeIf(String::isNotBlank)?.let(Access::ActAs)
                    ?: return "a grant to act as someone names the customer".left()
                else -> return "a grant is to view an account or to act as a customer".left()
            }
            return when {
                access is Access.ActAs && access.customer == asker.subject -> "nobody acts as themselves".left()
                facts["person"] != asker.subject -> "a grant is asked for by the person who will hold it".left()
                "support" !in asker.groupsList -> "only someone in support is given a grant".left()
                lasting == null || lasting <= Duration.ZERO -> "a grant says how long it lasts, as an ISO duration".left()
                lasting > LONGEST_GRANT -> "a grant lasts at most $LONGEST_GRANT".left()
                else -> Ask(asker.subject, access, lasting).right()
            }
        }
    }
}

/** The grants, in the read models' database: each request as asked, and each grant given from one. */
class JdbcGrants(private val data: DataSource) : Grants {
    override fun supports(person: String, account: String): Boolean = data.connection.use { connection ->
        connection.prepareStatement(
            "select 1 from access_grant where person = ? and kind = 'view' and account_id = ? and expires_at > now()",
        ).use { query ->
            query.setString(1, person)
            query.setString(2, account)
            query.executeQuery().use { it.next() }
        }
    }

    override fun actingUntil(person: String, customer: String): Instant? = data.connection.use { connection ->
        connection.prepareStatement(
            "select max(expires_at) from access_grant where person = ? and kind = 'act-as' and subject = ? and expires_at > now()",
        ).use { query ->
            query.setString(1, person)
            query.setString(2, customer)
            query.executeQuery().use { row -> if (row.next()) row.getTimestamp(1)?.toInstant() else null }
        }
    }

    override fun grantUsed(who: Caller, account: String?): String? = when (val actor = who.actor) {
        null -> if ("support" in who.groups && account != null) grant(who.subject, "view", "account_id", account) else null
        else -> grant(actor, "act-as", "subject", who.subject)
    }

    /** The live grant of [kind] [person] holds whose [column] is [value], the one lasting longest. */
    private fun grant(person: String, kind: String, column: String, value: String): String? = data.connection.use { connection ->
        connection.prepareStatement(
            "select approval_id from access_grant where person = ? and kind = ? and $column = ? and expires_at > now() " +
                "order by expires_at desc limit 1",
        ).use { query ->
            query.setString(1, person)
            query.setString(2, kind)
            query.setString(3, value)
            query.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
        }
    }

    /** [request] as the bank read it: the grant it asks for, or why it will not be given. Heard twice, kept once. */
    fun ask(request: String, hash: String, ask: Either<String, Ask>) = data.connection.use { connection ->
        connection.prepareStatement(
            "insert into access_request (request_id, content_hash, person, kind, account_id, subject, lasts_millis, refused) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (request_id) do nothing",
        ).use { insert ->
            val asked = ask.getOrNull()
            insert.setString(1, request)
            insert.setString(2, hash)
            insert.setString(3, asked?.person)
            insert.setString(4, asked?.access?.kind)
            insert.setString(5, (asked?.access as? Access.View)?.account)
            insert.setString(6, (asked?.access as? Access.ActAs)?.customer)
            insert.setObject(7, asked?.lasting?.toMillis())
            insert.setString(8, ask.leftOrNull())
            insert.executeUpdate()
        }
    }

    /** Whether the bank has heard [request] asked. */
    fun asked(request: String): Boolean = asking(request) != null

    /** [request] as asked, and its content hash: null if the bank never heard it. */
    fun asking(request: String): Pair<String, Either<String, Ask>>? = data.connection.use { connection ->
        connection.prepareStatement(
            "select content_hash, person, kind, account_id, subject, lasts_millis, refused from access_request where request_id = ?",
        ).use { query ->
            query.setString(1, request)
            query.executeQuery().use { row ->
                if (!row.next()) return@use null
                val refused = row.getString("refused")
                val ask = if (refused != null) refused.left()
                else {
                    val access = if (row.getString("kind") == "act-as") Access.ActAs(row.getString("subject"))
                    else Access.View(row.getString("account_id"))
                    Ask(row.getString("person"), access, Duration.ofMillis(row.getLong("lasts_millis"))).right()
                }
                row.getString("content_hash") to ask
            }
        }
    }

    /** The grant approval [approval] gave, until [expires]. Given again, it changes nothing. */
    fun give(approval: String, person: String, access: Access, expires: Instant) = data.connection.use { connection ->
        connection.prepareStatement(
            "insert into access_grant (approval_id, person, kind, account_id, subject, expires_at) values (?, ?, ?, ?, ?, ?) " +
                "on conflict (approval_id) do nothing",
        ).use { insert ->
            insert.setString(1, approval)
            insert.setString(2, person)
            insert.setString(3, access.kind)
            insert.setString(4, (access as? Access.View)?.account)
            insert.setString(5, (access as? Access.ActAs)?.customer)
            insert.setTimestamp(6, Timestamp.from(expires))
            insert.executeUpdate()
        }
    }

    /** The approval behind [person]'s live grant to view [account], the one lasting longest; null if none lives. */
    fun approvalFor(person: String, account: String): String? = grant(person, "view", "account_id", account)

    /** Who holds a grant for [account] that has not expired. */
    fun held(account: String): List<String> = data.connection.use { connection ->
        connection.prepareStatement("select person from access_grant where account_id = ? and expires_at > now() order by person")
            .use { query ->
                query.setString(1, account)
                query.executeQuery().use { row -> generateSequence { if (row.next()) row.getString(1) else null }.toList() }
            }
    }
}

/** What the bank tells Approvals of a grant: applied, or why not. */
interface Owner {
    fun applied(request: String, hash: String)

    fun applyFailed(request: String, reason: String)
}

/**
 * Approvals' `applied` and `apply-failed`, called with a token for the bank as a service: from [tokenUrl] with
 * [tokenForm], and [clientSecret] added when there is one. An answer Approvals refuses is logged and left: it asks
 * again if it still wants one. Approvals out of reach throws, so the event is heard again once it is back.
 */
class ApprovalsOwner(
    private val url: String,
    private val tokenUrl: String,
    private val tokenForm: String,
    private val clientSecret: String?,
) : Owner {
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    @Volatile private var token: Pair<String, Instant>? = null

    override fun applied(request: String, hash: String) =
        send(request, "applied", json.writeValueAsString(mapOf("hash" to hash)))

    override fun applyFailed(request: String, reason: String) =
        send(request, "apply-failed", json.writeValueAsString(mapOf("reason" to reason)))

    private fun send(request: String, what: String, body: String) {
        val path = "/requests/${URLEncoder.encode(request, Charsets.UTF_8)}/$what"
        val answer = post(path, body, token()).let { first ->
            if (first.statusCode() == UNAUTHORIZED) post(path, body, token(fresh = true)) else first
        }
        when (answer.statusCode()) {
            in OK -> counter("bank.access.told", "what" to what).increment()
            in CLIENT_ERROR -> logWarn("Approvals refused $what for $request: ${answer.statusCode()} ${answer.body()}")
            else -> throw IOException("Approvals answered $what for $request with ${answer.statusCode()}")
        }
    }

    private fun post(path: String, body: String, bearer: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(url.trimEnd('/') + path)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer $bearer")
            // Approvals continues the trace the grant's event arrived in (bank spec 0024).
            .apply { traceHeaders().forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** A token kept until shortly before it expires: one per few minutes, not one per event. */
    private fun token(fresh: Boolean = false): String {
        token?.takeIf { (_, until) -> !fresh && Instant.now().isBefore(until) }?.let { return it.first }
        val form = tokenForm + (clientSecret?.let { "&client_secret=${URLEncoder.encode(it, Charsets.UTF_8)}" } ?: "")
        val answer = http.send(
            HttpRequest.newBuilder(URI.create(tokenUrl)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (answer.statusCode() !in OK) throw IOException("no token for Approvals: ${answer.statusCode()} ${answer.body()}")
        val read = json.readTree(answer.body())
        val lasts = read.path("expires_in").asLong(DEFAULT_LIFETIME) - MARGIN
        return read.path("access_token").asText().also { token = it to Instant.now().plusSeconds(maxOf(lasts, 0)) }
    }

    private companion object {
        val OK = 200..299
        val CLIENT_ERROR = 400..499
        const val UNAUTHORIZED = 401
        const val DEFAULT_LIFETIME = 300L
        const val MARGIN = 30L
    }
}

/** What the bank does with each approval event: grants asked, and grants given or refused once approved. */
class GrantKeeper(private val grants: JdbcGrants, private val owner: Owner) {
    fun heard(event: ApprovalEvent) {
        if (event.kind != GRANT_KIND) return
        when (event.eventCase) {
            EventCase.REQUESTED -> grants.ask(event.requestId, event.requested.contentHash, Ask.of(event.requested))
            EventCase.APPROVAL_GIVEN -> approved(event, event.approvalGiven.contentHash)
            EventCase.AUTO_APPROVED -> approved(event, event.autoApproved.contentHash)
            // The rest change nothing here: a request never approved is never given. A case added after this was
            // built arrives unset, and is skipped.
            else -> Unit
        }
    }

    private fun approved(event: ApprovalEvent, hash: String) {
        val request = event.requestId
        val (asked, ask) = grants.asking(request)
            ?: return owner.applyFailed(request, "the bank never heard this grant asked for")
        if (asked != hash) return owner.applyFailed(request, "what was approved is not what was asked")
        ask.fold(
            { refused -> owner.applyFailed(request, refused) },
            { grant ->
                // From when it was approved, so a bank that hears it late gives what is left, not a fresh grant.
                val expires = Instant.ofEpochMilli(event.atMillis).plus(grant.lasting)
                grants.give(request, grant.person, grant.access, expires)
                logInfo("${grant.person} may ${grant.access} until $expires, on $request")
                owner.applied(request, hash)
            },
        )
    }
}

/** An Apicurio-framed record's message: a zero, the schema's content id, the message's indexes, then the message. */
fun unframed(bytes: ByteArray): ApprovalEvent {
    require(bytes.size >= HEADER && bytes[0] == 0.toByte()) { "not a framed record" }
    // The indexes are a count then each index, as zigzag varints; the topic's message is first, so a lone zero.
    val count = bytes[HEADER].toInt()
    require(count == 0) { "a message other than the file's first: $count" }
    return ApprovalEvent.parseFrom(bytes.copyOfRange(HEADER + 1, bytes.size))
}

private const val HEADER = 5

/** Every node reads its share of the approval events, and keeps or refuses each grant in them. */
fun grantsHeard(kafka: KafkaSettings, keeper: GrantKeeper, backend: StreamBackend): Running<Nothing, Long> {
    logInfo("hearing grants on ${ApprovalTopic.NAME}")
    return Kafka.consume(
        kafka.client + mapOf<String, Any>(
            ConsumerConfig.GROUP_ID_CONFIG to ApprovalTopic.GROUP,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            // Approvals makes its topic with its partitions; one made here first would have the broker's default.
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
        ),
        Topic(ApprovalTopic.NAME),
        key = StringDeserializer(),
        value = ByteArrayDeserializer(),
    )
        .mapRecord { record ->
            // In the trace the event was written in (spec 0024), so telling Approvals it applied continues it.
            record.within {
                // A record nobody can read is skipped, not read again forever; one that fails to apply is retried.
                Either.catch { unframed(record.value()) }.fold(
                    { unreadable -> logWarn("skipping ${ApprovalTopic.NAME} offset ${record.offset()}: ${unreadable.message}") },
                    keeper::heard,
                )
            }
        }
        .restartOnDefect(Schedule.spaced(2.seconds))
        .runCommitting()
        .start(backend)
}
