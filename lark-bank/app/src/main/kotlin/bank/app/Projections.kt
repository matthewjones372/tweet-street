package bank.app

import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.protocol.toContract
import bank.protocol.AccountEvents
import bank.protocol.Kinds
import bank.protocol.Nudge
import bank.protocol.TransferEvents
import bank.protocol.TransferMessage
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.SlicedFeed
import io.github.matthewjones372.lark.actor.OffsetStore
import io.github.matthewjones372.lark.actor.ShardedJournal
import io.github.matthewjones372.lark.actor.projection.Followed
import io.github.matthewjones372.lark.actor.projection.Projection
import io.github.matthewjones372.lark.actor.projection.runProjecting
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.stream.Running
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.groupedWithin
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.mapAsync
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.tick
import io.github.matthewjones372.lark.timed
import kotlin.time.Duration.Companion.seconds

/** The read models' names, which are also the offsets they save and what pruning waits for. */
object ReadModelNames {
    const val STATEMENTS = "statements"
    const val TRANSFERS = "transfers"
    const val PUBLISHED_ACCOUNTS = "published-accounts"
    const val PUBLISHED_TRANSFERS = "published-transfers"
}

/** A projection that throws — the database away for a moment — starts again from its last saved offset. */
private val again = Schedule.spaced<Throwable>(1.seconds)

/** One partition of one journal database's feed (spec 0014): what a read model follows, and saves its offset as. */
class Share(val feed: SlicedFeed, val database: String, val partition: Int, val partitions: Int) {
    /** The events of [kind] in this share, with the offset of read model [name] saved under its partition. */
    fun <E : Any> follow(offsets: OffsetStore, kind: String, codec: EventCodec<E>, name: String, settings: ReadModelSettings) =
        Projection.partitioned(
            feed, kind, codec, offsets, ShardedJournal.progress(name, database), partition, partitions, batch = settings.batch,
        )

    /** What read model [name]'s offset for this share is saved under. */
    fun progress(name: String) = ShardedJournal.progress(name, database, partition)
}

/**
 * Every event of [kind] in [share], a batch at a time: [write] takes the whole batch in one transaction, and only the
 * batch's last event reaches `runProjecting`, so one offset is saved per batch rather than one per event. Offsets only
 * grow along the feed, so the last one saved covers every event before it.
 */
fun <E : Any> batched(
    share: Share,
    offsets: OffsetStore,
    kind: String,
    codec: EventCodec<E>,
    name: String,
    settings: ReadModelSettings,
    write: (List<Sequenced<E>>) -> Int,
) = share.follow(offsets, kind, codec, name, settings)
    .groupedWithin(settings.batch, settings.within)
    .map { batch: List<Followed<E>> ->
        val written = timed("bank.projection.batch.duration", "projection" to share.progress(name)) {
            write(batch.map { Sequenced(it.id.id, it.sequence, it.value) })
        }
        saved(share.progress(name), batch, written)
    }
    .restartOnDefect(again)
    .runProjecting()

/** The batch's last event, for `runProjecting` to save, counted as [written] events of the projection [name]. */
private fun <E : Any> saved(name: String, batch: List<Followed<E>>, written: Int = batch.size): Followed<E> {
    counter("bank.projection.events", "projection" to name).increment(written.toDouble())
    gauge("bank.projection.offset", "projection" to name).set(batch.last().offset.toDouble())
    return batch.last()
}

/**
 * Every account event published to [EventStream.ACCOUNTS] (bank spec 0015), keyed by account so each account's
 * events stay in order. Events pass on in order once the transport has them, so an offset is saved only once every
 * event up to it is acknowledged; a restart sends the rest again, and never skips one.
 */
fun publishedAccounts(share: Share, offsets: OffsetStore, publisher: Publisher, settings: ReadModelSettings) =
    share.follow(offsets, Kinds.ACCOUNT, AccountEvents, ReadModelNames.PUBLISHED_ACCOUNTS, settings)
        .mapAsync(IN_FLIGHT) { followed ->
            val account = AccountId(followed.id.id)
            val event = followed.value.toContract(account, followed.sequence)
            publisher.publish(Outbound(EventStream.ACCOUNTS.topic, account.value, event, tracedOnly(followed.metadata)))
                .thenApply { followed }
        }
        .groupedWithin(settings.batch, settings.within)
        .map { batch: List<Followed<AccountEvent>> -> saved(share.progress(ReadModelNames.PUBLISHED_ACCOUNTS), batch) }
        .restartOnDefect(again)
        .runProjecting()

/** Every transfer event published to [EventStream.TRANSFERS], as [publishedAccounts], with its two accounts on it. */
fun publishedTransfers(share: Share, offsets: OffsetStore, publisher: Publisher, parties: TransferParties, settings: ReadModelSettings) =
    share.follow(offsets, Kinds.TRANSFER, TransferEvents, ReadModelNames.PUBLISHED_TRANSFERS, settings)
        .mapAsync(IN_FLIGHT) { followed ->
            val transfer = TransferId(followed.id.id)
            val (from, to) = parties.of(transfer, followed.value)
            val event = followed.value.toContract(transfer, followed.sequence, from, to)
            publisher.publish(Outbound(EventStream.TRANSFERS.topic, transfer.value, event, tracedOnly(followed.metadata)))
                .thenApply { followed }
        }
        .groupedWithin(settings.batch, settings.within)
        .map { batch: List<Followed<TransferEvent>> -> saved(share.progress(ReadModelNames.PUBLISHED_TRANSFERS), batch) }
        .restartOnDefect(again)
        .runProjecting()

/** Events waiting on the transport at once, so it can batch them. */
private const val IN_FLIGHT = 256

/**
 * Each transfer's two accounts, which only its `Requested` names: remembered from that event as it passes, and read
 * from the journal for a transfer whose `Requested` was published before this node's publisher started.
 */
class TransferParties(private val journal: Journal, private val remembered: Int = 50_000) {
    private val known = object : LinkedHashMap<String, Pair<AccountId, AccountId>>(1_024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<AccountId, AccountId>>) = size > remembered
    }

    fun of(transfer: TransferId, event: TransferEvent): Pair<AccountId, AccountId> = synchronized(known) {
        if (event is TransferEvent.Requested) known[transfer.value] = event.from to event.to
        known.getOrPut(transfer.value) { requested(transfer) }
    }

    private fun requested(transfer: TransferId): Pair<AccountId, AccountId> {
        val first = journal.read(PersistenceId(Kinds.TRANSFER, transfer.value)).firstOrNull()
            ?.let { stored -> TransferEvents.decode(stored.bytes) }
        check(first is TransferEvent.Requested) { "transfer ${transfer.value} does not begin with its request" }
        return first.from to first.to
    }
}

/** Wakes every saga that has not moved for a while: the ones whose node went away mid-transfer. */
fun sweeper(reads: JdbcReadModels, transfers: (String) -> ActorRef<TransferMessage>, settings: ReadModelSettings) =
    Stream.tick(settings.sweepEvery, Unit)
        .map { _ ->
            val stuck = reads.stuck(now() - settings.stuckAfter.inWholeMilliseconds, limit = 1_000)
            stuck.forEach { id -> transfers(id).tell(Nudge) }
            counter("bank.sweeper.nudged").increment(stuck.size.toDouble())
            stuck.size
        }
        .restartOnDefect(again)
        .runFold(0L) { nudged, now -> nudged + now }

/** What a read-models worker, or the sweeper's singleton, runs while it lives, and stops when it moves. */
class ReadModelRuns(private val runs: List<Running<*, *>>) : AutoCloseable {
    fun stop() = runs.forEach { running ->
        try {
            running.close()
        } catch (interrupted: InterruptedException) {
            logWarn("a read model was interrupted stopping: $interrupted")
            Thread.currentThread().interrupt()
        }
    }

    override fun close() = stop()
}

/**
 * Every journal database's share [partition] of [partitions]: what worker [worker] of the cluster's
 * `databases × partitions` read-model workers follows (spec 0014).
 */
fun share(journal: ShardedJournal, partitions: Int, worker: Int): Share {
    val (database, feed) = journal.feeds[worker / partitions]
    val sliced = requireNotNull(feed as? SlicedFeed) { "the journal of $database cannot be read by slice" }
    return Share(sliced, database, worker % partitions, partitions)
}

/**
 * Starts the read models of one [share] on [backend]: statements, transfers' status and, with a [publisher], every
 * account and transfer event published, each saving its own offset for the share. A worker the cluster places runs
 * them (spec 0014).
 */
fun startReadModels(
    share: Share,
    offsets: OffsetStore,
    reads: JdbcReadModels,
    settings: ReadModelSettings,
    publisher: Publisher?,
    parties: TransferParties,
    backend: StreamBackend,
): ReadModelRuns = ReadModelRuns(
    listOfNotNull(
        batched(share, offsets, Kinds.ACCOUNT, AccountEvents, ReadModelNames.STATEMENTS, settings, reads::project),
        batched(share, offsets, Kinds.TRANSFER, TransferEvents, ReadModelNames.TRANSFERS, settings, reads::projectTransfers),
        publisher?.let { publishedAccounts(share, offsets, it, settings) },
        publisher?.let { publishedTransfers(share, offsets, it, parties, settings) },
    ).map { it.start(backend) },
)

/** The sweeper, run once in the cluster: it reads the read models, not a feed. */
fun startSweeper(
    reads: JdbcReadModels,
    transfers: (String) -> ActorRef<TransferMessage>,
    settings: ReadModelSettings,
    backend: StreamBackend,
): ReadModelRuns = ReadModelRuns(listOf(sweeper(reads, transfers, settings).start(backend)))
