package bank.app

import bank.access.client.Access
import bank.access.client.Account
import bank.access.client.Answer
import bank.access.client.Asker
import bank.access.client.Decisions
import bank.access.client.Person
import bank.access.fga.Fga
import bank.api.Asked
import bank.api.Caller
import bank.api.Shadow
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logWarn
import io.micrometer.core.instrument.Metrics
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import io.github.matthewjones372.lark.Carriers

/**
 * Every question the bank's own rules answer put to bank-access too (bank spec 0022's access-shadow), off the request:
 * a request never waits for it, and never gets its answer. A disagreement is asked again after [recheckAfter], since a
 * relationship can trail what it is derived from (settled 4); one that still disagrees is counted and logged with both
 * answers. At most [most] questions are in flight; past that, one is skipped and counted, never queued.
 */
class ShadowedAccess(private val access: Access, private val recheckAfter: Duration, most: Int = 256) : Shadow {
    private val slots = Semaphore(most)
    private val asking = Executors.newVirtualThreadPerTaskExecutor()

    override fun compare(asked: Asked, who: Caller, target: String, local: Boolean) {
        if (!slots.tryAcquire()) {
            counter("bank.access.shadow.skipped").increment()
            return
        }
        // Asked on a thread of its own, inside the trace of the question it shadows (bank spec 0024).
        val carried = Carriers.capture()
        asking.execute {
            try {
                Carriers.within(carried) { judge(asked, who, target, local) }
            } finally {
                slots.release()
            }
        }
    }

    private fun judge(asked: Asked, who: Caller, target: String, local: Boolean) {
        val first = ask(asked, who, target) ?: return unanswered(asked)
        if (first == local) return agreed(asked)
        Thread.sleep(recheckAfter.toJavaDuration())
        val again = ask(asked, who, target) ?: return unanswered(asked)
        if (again == local) return agreed(asked)
        counter("bank.access.disagreed", "asked" to asked.label).increment()
        logWarn(
            "bank-access disagrees on ${asked.label} of $target for ${who.subject}" +
                (who.actor?.let { " (acting: $it)" } ?: "") + ": the bank says $local, bank-access $again",
        )
    }

    /** What bank-access answers, as the bank's rule means it; null when it does not answer. */
    private fun ask(asked: Asked, who: Caller, target: String): Boolean? {
        val answer = when (asked) {
            Asked.ViewAccount -> access.check(Asker(who.subject, who.actor, who.groups), Account.viewer, target)
            // Paying while acting as someone is the bank's own refusal, whatever the model says of the customer.
            Asked.PayFromAccount -> if (who.isImpersonating) Answer.No else access.check(Asker(who.subject), Account.payer, target)
            Asked.ActAs -> access.check(Asker(checkNotNull(who.actor)), Person.impersonator, target)
        }
        return when (answer) {
            Answer.Yes -> true
            Answer.No -> false
            is Answer.Unanswered -> null
        }
    }

    private fun agreed(asked: Asked) = counter("bank.access.shadow", "asked" to asked.label, "answer" to "agreed").increment()

    private fun unanswered(asked: Asked) = counter("bank.access.shadow", "asked" to asked.label, "answer" to "unanswered").increment()

    companion object {
        fun of(settings: AccessShadowSettings): Shadow =
            if (!settings.enabled) Shadow.none
            else ShadowedAccess(
                // Decisions are kept once a rule is answered by bank-access; in shadow, the bank's answer is the one used.
                Access(
                    Fga(settings.url, settings.token, settings.store, Access.BUDGET, carried = ::traceHeaders),
                    Decisions.none,
                    Metrics.globalRegistry,
                ),
                settings.recheckAfter,
            )
    }
}
