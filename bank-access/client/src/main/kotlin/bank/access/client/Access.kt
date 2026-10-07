package bank.access.client

import bank.access.fga.Fga
import bank.access.fga.FgaUnavailable
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is asking: [subject], whose relation it is; [actor] when someone else is acting as them; their [groups], which
 * decide whether the decision is kept; and the [address] it came from.
 */
data class Asker(
    val subject: String,
    val actor: String? = null,
    val groups: Set<String> = emptySet(),
    val address: String? = null,
) {
    /** Staff, or someone acting as another: every decision about them is kept (spec 0022, settled 7). */
    val accountable: Boolean get() = actor != null || groups.any { it in STAFF }

    companion object {
        val STAFF = setOf("support", "ops", "auditor", "admins", "risk")
    }
}

/** What bank-access answered. Unanswered is never yes: a service refuses the request, with [status]. */
sealed interface Answer {
    data object Yes : Answer

    data object No : Answer

    data class Unanswered(val because: String) : Answer {
        /** What the request is refused with: the service cannot answer, not "forbidden". */
        val status: Int get() = 503
    }
}

/** One request's answers, so it asks each question once however often its handlers ask it. */
class Memo {
    internal val answers = ConcurrentHashMap<String, Answer>()
}

/**
 * bank-access as a service asks it (lark-bank spec 0022): [check] and [listObjects] against the store [fga] names,
 * each within the [fga]'s timeout, which is the request's budget for it. No answer in time is [Answer.Unanswered],
 * never yes. Every refusal, and every check by staff or one acting, is kept in [decisions].
 */
class Access(
    private val fga: Fga,
    private val decisions: Decisions,
    private val registry: MeterRegistry,
) {
    private val unanswered = registry.counter("bank.access.unanswered")
    private val timer = Timer.builder("bank.access.check.duration").publishPercentileHistogram().register(registry)

    /**
     * Whether [who] has [relation] to the object [id] of its type. Someone acting as [Asker.subject] is asked about
     * first: their grant to act, then the subject's own relation. What one acting may not do although the subject may
     * (pay, change details) stays the service's own rule.
     */
    fun check(who: Asker, relation: Relation, id: String, memo: Memo? = null): Answer {
        val obj = "${relation.type}:$id"
        val key = "${who.subject} ${who.actor} $relation $obj"
        memo?.answers?.get(key)?.let { return it }
        val answer = timer.recordCallable { ask(who, relation, obj) }!!
        registry.counter("bank.access.checks", "relation", relation.toString(), "answer", answer.label).increment()
        if (answer is Answer.Unanswered) unanswered.increment()
        if (answer !is Answer.Yes || who.accountable) decisions.record(who, relation, obj, answer)
        memo?.answers?.put(key, answer)
        return answer
    }

    /** Every object of [relation]'s type [who] has it to; nothing at all when bank-access does not answer. */
    fun listObjects(who: Asker, relation: Relation): Set<String> = try {
        if (who.actor != null && !fga.check("person:${who.actor}", Person.impersonator.name, "person:${who.subject}")) emptySet()
        else fga.listObjects("person:${who.subject}", relation.name, relation.type).map { it.substringAfter(':') }.toSet()
    } catch (failed: FgaUnavailable) {
        unanswered.increment()
        emptySet()
    }

    private fun ask(who: Asker, relation: Relation, obj: String): Answer = try {
        val acting = who.actor?.let { fga.check("person:$it", Person.impersonator.name, "person:${who.subject}") } ?: true
        if (acting && fga.check("person:${who.subject}", relation.name, obj)) Answer.Yes else Answer.No
    } catch (failed: FgaUnavailable) {
        Answer.Unanswered(failed.message ?: "bank-access did not answer")
    }

    private val Answer.label: String
        get() = when (this) {
            Answer.Yes -> "yes"
            Answer.No -> "no"
            is Answer.Unanswered -> "unanswered"
        }

    companion object {
        /** Each check's share of a request (spec 0022): past it, the request is refused rather than kept waiting. */
        val BUDGET: Duration = Duration.ofMillis(50)
    }
}
