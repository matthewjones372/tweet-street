package bank.protocol

import bank.domain.AccountEvent
import io.github.matthewjones372.lark.actor.UnreadableEvent
import io.github.matthewjones372.lark.actor.Upgrade
import io.github.matthewjones372.lark.actor.versioned
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** The journal's codecs are versioned (lark spec 0091), and what that promises the bank's journal. */
class VersionedJournalSpec {

    private val deposit = AccountEvent.Deposited(gbp(500), "ref-1", 2)

    @Test
    fun `an event written before the codecs were versioned is read as version 1`() {
        AccountEvents.decode(AccountEventBody.encode(deposit)) shouldBe deposit
    }

    @Test
    fun `an event written today is marked with its version, and reads back`() {
        val written = AccountEvents.encode(deposit)

        written shouldNotBe AccountEventBody.encode(deposit)
        AccountEvents.decode(written) shouldBe deposit
    }

    @Test
    fun `a version 2 reads every event written today through its upgrade from 1`() {
        // A version 2 whose shape is today's, so its upgrade from 1 is the bytes as they are.
        val next = versioned(JOURNAL_VERSION + 1, AccountEventBody, mapOf(1 to Upgrade { listOf(it) }))

        next.decodeAll(AccountEvents.encode(deposit)) shouldBe listOf(deposit)
        next.decodeAll(AccountEventBody.encode(deposit)) shouldBe listOf(deposit)
    }

    @Test
    fun `an event of a version newer than the codec is refused, not misread`() {
        val next = versioned(JOURNAL_VERSION + 1, AccountEventBody, mapOf(1 to Upgrade { listOf(it) }))

        shouldThrow<UnreadableEvent> { AccountEvents.decode(next.encode(deposit)) }
    }
}
