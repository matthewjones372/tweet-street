package bank.app

import bank.api.OpsJournal
import bank.api.OpsNode
import bank.api.OpsSnapshot
import bank.protocol.wire.NodeSnapshot
import io.github.matthewjones372.lark.actor.OffsetStore
import io.github.matthewjones372.lark.actor.ShardedJournal
import io.github.matthewjones372.lark.actor.Topic
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.tick
import io.github.matthewjones372.lark.Reading
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.readings
import bank.domain.Currencies
import java.math.BigDecimal
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/**
 * This node's own numbers once a second (bank spec 0007): the shards and entities lark's gauges count here (lark
 * 0081), and the rates since the last snapshot of what the bank's own meters count. Rates are deltas between two
 * readings of cumulative meters, so one snapshot is taken at a time.
 */
class NodeMeter(private val node: String, private val cluster: Cluster) {
    private var lastAt = System.nanoTime()
    private var asked = 0L
    private var buckets = emptyMap<Double, Double>()
    private var commands = emptyMap<String, Double>()

    @Synchronized
    fun snapshot(): NodeSnapshot {
        val now = System.nanoTime()
        val seconds = ((now - lastAt) / NANOS_PER_SECOND).coerceAtLeast(MIN_SECONDS)
        lastAt = now

        // lark's `timed` records milliseconds as a histogram, read back with its buckets (lark spec 0109).
        val asks = readings("bank.ask.duration").filterIsInstance<Reading.Distribution>()
        val count = asks.sumOf { it.count }
        val cumulative = asks
            .flatMap { it.buckets.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, counts) -> counts.sum() }
        val p99 = quantile(cumulative.mapValues { (bound, total) -> total - (buckets[bound] ?: 0.0) }, P99)
        val askedNow = count - asked
        asked = count
        buckets = cumulative

        val counted = readings("bank.account.commands").filterIsInstance<Reading.Total>()
            .groupBy({ it.tags["outcome"].orEmpty() }, { it.total })
            .mapValues { (_, counts) -> counts.sum() }
        val commandRates = counted.mapValues { (outcome, total) -> (total - (commands[outcome] ?: 0.0)) / seconds }
        commands = counted

        return NodeSnapshot(
            node = node,
            status = cluster.view.members.firstOrNull { it.node == cluster.self }?.status?.name ?: "Joining",
            atMillis = System.currentTimeMillis(),
            shards = gauges("lark.sharding.shards"),
            entities = gauges("lark.sharding.entities"),
            asksPerSecond = askedNow / seconds,
            askP99Millis = p99,
            commandsPerSecond = commandRates,
        )
    }

    private fun gauges(name: String): Map<String, Long> =
        readings(name).filterIsInstance<Reading.Value>().associate { it.tags["kind"].orEmpty() to it.value.toLong() }

    private companion object {
        const val NANOS_PER_SECOND = 1e9
        const val MIN_SECONDS = 1e-3
        const val P99 = 0.99
    }
}

/** The smallest bucket bound under which [quantile] of the counts fall; 0 when nothing was counted. */
internal fun quantile(counts: Map<Double, Double>, quantile: Double): Double {
    val ordered = counts.entries.sortedBy { it.key }
    val total = ordered.lastOrNull()?.value ?: 0.0
    if (total <= 0.0) return 0.0
    return ordered.first { it.value >= total * quantile }.key
}

/** Publishes this node's snapshot to the `ops` topic once a second, for as long as the node runs. */
fun opsPublisher(meter: NodeMeter, topic: Topic<NodeSnapshot>, backend: StreamBackend): Running<Nothing, Long> =
    Stream.tick(1.seconds, Unit)
        .map { _ ->
            topic.publish(meter.snapshot())
            1L
        }
        .restartOnDefect(io.github.matthewjones372.lark.Schedule.spaced(1.seconds))
        .runFold(0L) { published, now -> published + now }
        .start(backend)

/** Each journal database's newest ordering: how far its feed goes, for the lag of what reads it. */
class JournalHeads(private val sources: List<Pair<String, DataSource>>) {
    val databases: List<String> = sources.map { it.first }

    fun heads(): List<Pair<String, Long>> = sources.map { (name, data) ->
        name to data.connection.use { connection ->
            connection.createStatement().use { query ->
                query.executeQuery("select coalesce(max(ordering), 0) from lark_journal").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }
    }
}

/**
 * The bank as one snapshot: every node's latest word of itself from the `ops` topic, and what this node answers for
 * all of them, the ledger, the transfers, and the journal's lag.
 */
class LiveOps(
    private val node: String,
    private val latest: ConcurrentHashMap<String, NodeSnapshot>,
    private val views: ClusterViews,
    private val reads: JdbcReadModels,
    private val heads: JournalHeads,
    private val behind: Behind,
    private val readers: List<String>,
    private val running: List<Running<Nothing, Long>>,
) {
    fun current(): OpsSnapshot {
        val now = System.currentTimeMillis()
        val view = views.current()
        return OpsSnapshot(
            atMillis = now,
            servedBy = node,
            leader = view.leader,
            readModelsOn = view.readModelsOn,
            unreachable = view.unreachable,
            // A node gone quiet stays on the page, aging, until it has been silent for a minute.
            nodes = latest.values.filter { now - it.atMillis < FORGET_AFTER_MILLIS }.sortedBy { it.node }.map { said ->
                OpsNode(
                    said.node, said.status, now - said.atMillis, said.shards, said.entities, said.asksPerSecond,
                    said.askP99Millis, said.commandsPerSecond,
                )
            },
            ledger = reads.ledger(),
            transfers = reads.transfers(),
            journal = heads.heads().map { (database, head) ->
                OpsJournal(
                    database,
                    head,
                    readers.associateWith { reader ->
                        behind.lag(reader, database, head)
                    },
                )
            },
        )
    }

    fun close() = running.forEach { it.close() }

    private companion object {
        const val FORGET_AFTER_MILLIS = 60_000L
    }
}

/**
 * The books as gauges, for alerts to watch (bank spec 0009): how far the ledger is from balancing, how many transfers
 * are still moving, and how far each read model trails each journal database. Read every [every], since each is a
 * query, and set through lark. Each starts at NaN, and a database that does not answer leaves the last reading:
 * never a false 0.
 */
fun bookGauges(
    currencies: Currencies,
    reads: JdbcReadModels,
    heads: JournalHeads,
    behind: Behind,
    readers: List<String>,
    backend: StreamBackend,
    every: kotlin.time.Duration = 10.seconds,
): Running<Nothing, Long> {
    fun gap(code: String) = gauge("bank.ledger.gap", "currency" to code)
    fun lag(database: String, reader: String) = gauge("bank.journal.lag", "database" to database, "reader" to reader)
    val pending = gauge("bank.transfers.pending")
    currencies.all.forEach { gap(it.currency.code).set(Double.NaN) }
    pending.set(Double.NaN)
    heads.databases.forEach { database -> readers.forEach { reader -> lag(database, reader).set(Double.NaN) } }
    fun read(): Long = try {
        val gaps = reads.books().associate { it.currency.code to it.gap }
        // A currency nobody holds yet has no books, and nothing out of balance.
        currencies.all.forEach { listed ->
            gap(listed.currency.code).set((gaps[listed.currency.code] ?: BigDecimal.ZERO).toDouble())
        }
        pending.set(reads.transfers().pending.toDouble())
        heads.heads().forEach { (database, head) ->
            readers.forEach { reader -> lag(database, reader).set(behind.lag(reader, database, head).toDouble()) }
        }
        1L
    } catch (unanswered: SQLException) {
        logWarn("the books could not be read for their gauges: $unanswered")
        0L
    }
    // Read once now, so a scrape straight after start sees the books, then on every tick.
    val first = read()
    return Stream.tick(every, Unit)
        .map { _ -> read() }
        .restartOnDefect(io.github.matthewjones372.lark.Schedule.spaced(every))
        .runFold(first) { total, now -> total + now }
        .start(backend)
}

/**
 * How far behind a database's newest event each read model is: the furthest behind of its partitions (spec 0014),
 * each of which saves its own offset, a place in the database's whole feed.
 */
class Behind(private val offsets: OffsetStore, private val partitions: Int) {
    fun lag(reader: String, database: String, head: Long): Long = (0 until partitions).maxOf { partition ->
        head - (offsets.load(ShardedJournal.progress(reader, database, partition)) ?: 0)
    }.coerceAtLeast(0)
}
