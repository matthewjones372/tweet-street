package bank.app

import bank.api.AccessLog
import bank.api.Caller
import bank.api.Logged
import bank.api.Look
import bank.events.v1.AccessEvent
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.tick
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The access log in the read models' database (bank spec 0021). */
class JdbcAccessLog(private val data: DataSource) : AccessLog {
    override fun record(look: Look) {
        try {
            data.connection.use { connection ->
                connection.prepareStatement(
                    "insert into access_log (at, subject, name, groups, actor, endpoint, account_id, status, address, user_agent, grant_id) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { insert ->
                    insert.setTimestamp(1, Timestamp.from(look.at))
                    insert.setString(2, look.who.subject)
                    insert.setString(3, look.who.name)
                    insert.setArray(4, connection.createArrayOf("text", look.who.groups.sorted().toTypedArray()))
                    insert.setString(5, look.who.actor)
                    insert.setString(6, look.endpoint)
                    insert.setString(7, look.account)
                    insert.setInt(8, look.status)
                    insert.setString(9, look.address)
                    insert.setString(10, look.userAgent)
                    insert.setString(11, look.grant)
                    insert.executeUpdate()
                }
            }
            counter("bank.access.recorded").increment()
        } catch (failed: java.sql.SQLException) {
            // The answer has gone already; a look not recorded is counted and alerted on, never silent.
            counter("bank.access.unrecorded").increment()
            logWarn("a look by ${look.who.actor ?: look.who.subject} at ${look.endpoint} was not recorded: ${failed.message}")
        }
    }

    override fun looksAt(account: String, limit: Int): List<Logged> = data.connection.use { connection ->
        connection.prepareStatement("select * from access_log where account_id = ? order by id desc limit ?").use { query ->
            query.setString(1, account)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.logged() else null }.toList() }
        }
    }

    /**
     * Up to [most] looks not yet on `bank.access-events`, handed to [publish], and marked once all are there. Rows are
     * locked while they go, and skipped by any other node publishing at once, so each goes once from here; a crash
     * between sending and marking sends them again, which a consumer dedupes on the id.
     */
    fun publishSome(most: Int, publish: (List<Logged>) -> Unit): Int = data.connection.use { connection ->
        connection.autoCommit = false
        try {
            val due = connection.prepareStatement(
                "select * from access_log where published_at is null order by id limit ? for update skip locked",
            ).use { query ->
                query.setInt(1, most)
                query.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.logged() else null }.toList() }
            }
            if (due.isNotEmpty()) {
                publish(due)
                connection.prepareStatement("update access_log set published_at = now() where id = any(?)").use { mark ->
                    mark.setArray(1, connection.createArrayOf("bigint", due.map { it.id }.toTypedArray()))
                    mark.executeUpdate()
                }
            }
            connection.commit()
            due.size
        } catch (failed: Exception) {
            connection.rollback()
            throw failed
        } finally {
            connection.autoCommit = true
        }
    }

    private fun ResultSet.logged(): Logged {
        @Suppress("UNCHECKED_CAST")
        val groups = (getArray("groups").array as Array<String>).toSet()
        val who = Caller(getString("subject"), getString("name"), groups, getString("actor"))
        return Logged(
            getLong("id"),
            Look(
                getTimestamp("at").toInstant(), who, getString("endpoint"), getString("account_id"), getInt("status"),
                getString("address"), getString("user_agent"), getString("grant_id"),
            ),
        )
    }
}

/** A logged look as the topic carries it. */
fun Logged.toEvent(): AccessEvent = AccessEvent.newBuilder().also { event ->
    event.id = id
    event.atMillis = look.at.toEpochMilli()
    // Who was really there: the person acting, when someone was.
    event.who = AccessEvent.Person.newBuilder()
        .setSubject(look.who.actor ?: look.who.subject).setName(look.who.actor ?: look.who.name)
        .addAllGroups(look.who.groups.sorted()).build()
    look.who.actor?.let { event.actingAs = look.who.subject }
    event.endpoint = look.endpoint
    look.account?.let { event.accountId = it }
    event.status = look.status
    look.address?.let { event.address = it }
    look.userAgent?.let { event.userAgent = it }
    look.grant?.let { event.grantApprovalId = it }
}.build()

/** The outbox: every [every], this node publishes what no node has yet, a batch at a time, while it runs. */
fun accessPublisher(log: JdbcAccessLog, publisher: Publisher, backend: StreamBackend, every: Duration = 1.seconds): Running<Nothing, Long> =
    Stream.tick(every, Unit)
        .map { _ ->
            log.publishSome(BATCH) { due ->
                val sent = due.map { logged ->
                    val key = logged.look.account ?: logged.look.who.subject
                    publisher.publish(Outbound(EventStream.ACCESS.topic, key, logged.toEvent()))
                }
                CompletableFuture.allOf(*sent.toTypedArray()).join()
                counter("bank.access.published").increment(due.size.toDouble())
            }.toLong()
        }
        .restartOnDefect(Schedule.spaced(every))
        .runFold(0L) { published, now -> published + now }
        .start(backend)

private const val BATCH = 500
