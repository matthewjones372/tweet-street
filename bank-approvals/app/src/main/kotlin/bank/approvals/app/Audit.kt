package bank.approvals.app

import bank.approvals.api.Audit
import bank.approvals.api.AuditEntry
import bank.approvals.api.AuditFilter
import bank.approvals.api.Histories
import bank.approvals.api.Broken
import bank.approvals.api.Verification
import bank.approvals.api.auditEntry
import bank.approvals.domain.Chain
import bank.approvals.domain.Origin
import bank.approvals.domain.Person
import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.approvals.protocol.Kinds
import bank.approvals.protocol.RequestEvents
import bank.approvals.protocol.StoredLinks
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.actor.FeedEvent
import io.github.matthewjones372.lark.actor.JournalFeed
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.tick
import java.security.MessageDigest
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * The audit (lark-bank spec 0019), read from the journal itself: every request's chain walked and checked, each day's
 * chain heads hashed into a digest kept in its own table, and the auditor's entries. Approvals holds a few events per
 * change, so each answer walks them all; a read model when that stops being cheap.
 */

/** One request's chain at the end of a day: its last event that day, and that event's link. */
data class Head(val request: String, val sequence: Long, val link: String)

/** A day's digest: the SHA-256 of every head of every chain changed that day. */
data class Digest(val day: LocalDate, val digest: String, val heads: List<Head>)

class JournalAudit(private val feed: JournalFeed, private val data: DataSource) : Audit, Histories {

    /** Every request's events as they read now; one that no longer reads is left out, and `verify` names it. */
    override fun all(): Map<RequestId, List<RequestEvent>> = stored().mapNotNull { (id, events) ->
        runCatching { RequestId(id) to events.map { RequestEvents.decode(it.bytes).event } }.getOrNull()
    }.toMap()

    /** Every request's stored events, by request, in sequence order. */
    private fun stored(): Map<String, List<FeedEvent>> {
        val all = mutableListOf<FeedEvent>()
        var offset = 0L
        while (true) {
            val page = feed.after(Kinds.REQUEST, offset, PAGE)
            if (page.isEmpty()) break
            all += page
            offset = page.last().offset
        }
        return all.groupBy { it.id.id }.mapValues { (_, events) -> events.sortedBy { it.sequence } }.toSortedMap()
    }

    /** What walking one chain found: the links that follow, in order, up to the first that does not, which is [broken]. */
    private class Walked(val links: List<String>, val broken: Broken?)

    private fun walk(id: String, events: List<FeedEvent>): Walked {
        val links = mutableListOf<String>()
        var previous: String? = null
        events.forEachIndexed { index, event ->
            val expected = index + 1L
            if (event.sequence != expected) {
                return Walked(links, Broken(id, expected, "missing: the chain goes on at ${event.sequence}"))
            }
            val stored = runCatching { StoredLinks.decode(event.bytes) }.getOrNull()
                ?: return Walked(links, Broken(id, event.sequence, "does not read as an event"))
            val link = Chain.link(previous, stored.event.toHexString())
            if (link != stored.link) return Walked(links, Broken(id, event.sequence, "its bytes, or the chain before it, do not match its link"))
            links += link
            previous = link
        }
        return Walked(links, null)
    }

    override fun verify(): Verification {
        val requests = stored()
        val walked = requests.mapValues { (id, events) -> walk(id, events) }
        val broken = linkedMapOf<String, Broken>()
        walked.values.mapNotNull { it.broken }.forEach { broken[it.request] = it }
        val digests = digests()
        digests.forEach { digest ->
            if (digestOf(digest.heads) != digest.digest) {
                broken.putIfAbsent("digest ${digest.day}", Broken("digest ${digest.day}", 0, "the digest does not match the heads recorded with it"))
            }
            digest.heads.forEach { head ->
                // A chain rewritten whole, every link recomputed, still walks; its head on that day no longer matches.
                if (walked[head.request]?.links?.getOrNull((head.sequence - 1).toInt()) != head.link) {
                    broken.putIfAbsent(head.request, Broken(head.request, head.sequence, "does not match the digest of ${digest.day}"))
                }
            }
        }
        return Verification(requests.size, requests.values.sumOf { it.size }, digests.size, broken.values.toList())
    }

    /** The heads of every chain changed on [day], from the journal as it is now. */
    private fun heads(day: LocalDate, requests: Map<String, List<FeedEvent>>): List<Head> = requests.mapNotNull { (id, events) ->
        events.lastOrNull { event -> dayOf(event) == day }?.let { last -> Head(id, last.sequence, StoredLinks.decode(last.bytes).link) }
    }

    private fun dayOf(event: FeedEvent): LocalDate? =
        runCatching { RequestEvents.decode(event.bytes).event.at.atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()

    /** [day]'s digest, made and kept if it is not already; a day already digested is never digested again. */
    fun digest(day: LocalDate): Digest = digests().firstOrNull { it.day == day } ?: run {
        val heads = heads(day, stored())
        val digest = Digest(day, digestOf(heads), heads)
        data.connection.use { connection ->
            connection.autoCommit = false
            val made = connection.prepareStatement(
                "insert into approval_digest (day, digest, heads, made_at) values (?, ?, ?, ?) on conflict (day) do nothing",
            ).use {
                it.setDate(1, Date.valueOf(day))
                it.setString(2, digest.digest)
                it.setInt(3, heads.size)
                it.setTimestamp(4, Timestamp.from(Instant.now()))
                it.executeUpdate()
            }
            if (made == 1) {
                connection.prepareStatement("insert into approval_digest_head (day, request_id, sequence, link) values (?, ?, ?, ?)").use {
                    heads.forEach { head ->
                        it.setDate(1, Date.valueOf(day))
                        it.setString(2, head.request)
                        it.setLong(3, head.sequence)
                        it.setString(4, head.link)
                        it.addBatch()
                    }
                    it.executeBatch()
                }
            }
            connection.commit()
            if (made == 1) logInfo("approvals digest of $day: ${digest.digest} over ${heads.size} chains")
        }
        digests().first { it.day == day }
    }

    /** Every day not yet digested, from the first event's or the day after the last digest's, through [last]. */
    fun digestThrough(last: LocalDate): Int {
        val since = digests().maxOfOrNull { it.day }?.plusDays(1)
            ?: stored().values.flatten().mapNotNull(::dayOf).minOrNull()
            ?: return 0
        return generateSequence(since) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.onEach(::digest).count()
    }

    fun digests(): List<Digest> = data.connection.use { connection ->
        val heads = connection.prepareStatement("select day, request_id, sequence, link from approval_digest_head").use {
            it.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getDate(1).toLocalDate() to Head(rows.getString(2), rows.getLong(3), rows.getString(4)) else null }
                    .toList()
            }
        }.groupBy({ it.first }, { it.second })
        connection.prepareStatement("select day, digest from approval_digest order by day").use {
            it.executeQuery().use { rows ->
                generateSequence {
                    if (rows.next()) rows.getDate(1).toLocalDate().let { day -> Digest(day, rows.getString(2), heads[day].orEmpty()) } else null
                }.toList()
            }
        }
    }

    override fun entries(filter: AuditFilter): List<AuditEntry> = stored().flatMap { (id, events) ->
        if (filter.request != null && filter.request != id) return@flatMap emptyList()
        val decoded = events.mapNotNull { event -> runCatching { event.sequence to RequestEvents.decode(event.bytes).event }.getOrNull() }
        val asked = (decoded.firstOrNull()?.second as? RequestEvent.Requested)?.asked ?: return@flatMap emptyList()
        if (filter.subject != null && filter.subject != asked.proposal.subject) return@flatMap emptyList()
        decoded
            .filter { (_, event) -> within(event.at, filter) }
            .map { (sequence, event) -> event.at to auditEntry(id, sequence, asked.proposal.kind, asked.proposal.subject, event) }
            .filter { (_, entry) -> filter.person == null || entry.who?.subject == filter.person }
    }.sortedWith(compareBy({ it.first }, { it.second.request }, { it.second.sequence })).map { it.second }

    private fun within(at: Instant, filter: AuditFilter): Boolean {
        val day = at.atZone(ZoneOffset.UTC).toLocalDate()
        return (filter.from == null || !day.isBefore(filter.from)) && (filter.to == null || !day.isAfter(filter.to))
    }

    override fun exported(by: Person, origin: Origin, filter: AuditFilter, format: String, rows: Int) {
        data.connection.use { connection ->
            connection.prepareStatement(
                "insert into audit_export (at, subject, name, session, address, user_agent, filter, format, rows) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setTimestamp(1, Timestamp.from(Instant.now()))
                it.setString(2, by.subject)
                it.setString(3, by.name)
                it.setString(4, origin.session)
                it.setString(5, origin.address)
                it.setString(6, origin.userAgent)
                it.setString(7, filter.toString())
                it.setString(8, format)
                it.setInt(9, rows)
                it.executeUpdate()
            }
        }
        logInfo("${by.subject} exported $rows audit entries as $format: $filter")
    }

    /** How many exports [subject] has made: each is on the record. */
    fun exportsBy(subject: String): Int = data.connection.use { connection ->
        connection.prepareStatement("select count(*) from audit_export where subject = ?").use {
            it.setString(1, subject)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    companion object {
        private const val PAGE = 1_000

        /** The SHA-256 of each head as "request sequence link", sorted by request and joined by newlines, as hex. */
        fun digestOf(heads: List<Head>): String =
            MessageDigest.getInstance("SHA-256")
                .digest(heads.sortedBy { it.request }.joinToString("\n") { "${it.request} ${it.sequence} ${it.link}" }.toByteArray())
                .toHexString()
    }
}

/** A run that throws (the database away for a moment) starts again. */
private val again = Schedule.spaced<Throwable>(1.seconds)

/** Every [every], each day before today (UTC) not yet digested, digested: the nightly digest, caught up if it was missed. */
fun digesting(audit: JournalAudit, every: Duration) =
    Stream.tick(every, Unit)
        .map { _ -> audit.digestThrough(LocalDate.now(ZoneOffset.UTC).minusDays(1)).toLong() }
        .restartOnDefect(again)
        .runFold(0L, Long::plus)
