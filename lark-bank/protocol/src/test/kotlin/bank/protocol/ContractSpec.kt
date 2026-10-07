package bank.protocol

import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Currencies
import bank.domain.Currency
import bank.domain.Listed
import bank.domain.Money
import bank.domain.ScreeningOutcome
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.events.v1.AccountEvent as AccountRecord
import bank.events.v1.TransferEvent as TransferRecord
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/** Bank spec 0015: every domain event as its Protobuf message, and back, exactly. */
class ContractSpec {
    private val currencies = Currencies(listOf(Listed(GBP, "£"), Listed(BTC, "₿", Currency.Kind.Crypto)))
    private val btc = Money.ofMinor(12_000, BTC)
    private val transfer = TransferId("t-1")

    private val accountEvents = listOf(
        AccountEvent.Opened("Ada", gbp(10_050), "open:a-1", 1_000),
        AccountEvent.Deposited(btc, "d-1", 1_001),
        AccountEvent.Withdrawn(gbp(1), "w-1", 1_002),
        AccountEvent.Debited(transfer, gbp(500), 1_003),
        AccountEvent.Credited(transfer, gbp(500), 1_004),
        AccountEvent.Refunded(transfer, gbp(500), 1_005),
        AccountEvent.LegsClosed(transfer, 1_006),
    )

    private val transferEvents = listOf(
        TransferEvent.Requested(AccountId("a-1"), AccountId("a-2"), gbp(500), 2_000),
        TransferEvent.SourceDebited(2_001),
        TransferEvent.Rejected("Account a-1 has 1.00 GBP, not 5.00 GBP", 2_002),
        TransferEvent.DestinationCredited(2_003),
        TransferEvent.CreditRefused("No account a-2", 2_004),
        TransferEvent.SourceRefunded(2_005),
        TransferEvent.Screened(ScreeningOutcome.Declined, "large-transfer", 3, "amount >= 1000.00", answered = true, atMillis = 2_006),
        TransferEvent.Screened(ScreeningOutcome.Approved, null, null, "", answered = false, atMillis = 2_007),
    )

    @Test
    fun `every account event survives the wire as itself, in its account and place`() {
        accountEvents.forEachIndexed { i, event ->
            val bytes = event.toContract(AccountId("a-1"), i + 1L).toByteArray()
            val back = AccountRecord.parseFrom(bytes).fromContract(currencies).shouldBeRight()
            back shouldBe FromContract("a-1", i + 1L, event)
        }
    }

    @Test
    fun `every transfer event survives the wire as itself, with both its accounts on it`() {
        transferEvents.forEachIndexed { i, event ->
            val record = TransferRecord.parseFrom(event.toContract(transfer, i + 1L, AccountId("a-1"), AccountId("a-2")).toByteArray())
            record.fromAccount shouldBe "a-1"
            record.toAccount shouldBe "a-2"
            record.fromContract(currencies).shouldBeRight() shouldBe FromContract("t-1", i + 1L, event)
        }
    }

    @Test
    fun `money is a plain decimal at its currency's scale, which a consumer reads exactly`() {
        gbp(10_050).toContract().amount shouldBe "100.50"
        gbp(0).toContract().amount shouldBe "0.00"
        btc.toContract().amount shouldBe "0.00012000"
        java.math.BigDecimal(btc.toContract().amount) shouldBe btc.amount
    }

    @Test
    fun `an event of a kind this build does not know is told apart, for a consumer to skip`() {
        val later = AccountRecord.newBuilder().setAccountId("a-1").setSequence(9).build()
        later.fromContract(currencies).shouldBeLeft().shouldBeInstanceOf<ContractError.Unknown>()
    }

    @Test
    fun `no domain event goes unpublished, since every kind of event is in the round trips above`() {
        covered(AccountEvent::class, accountEvents) shouldBe emptySet()
        covered(TransferEvent::class, transferEvents) shouldBe emptySet()
    }

    /** The kinds of [root] that none of [samples] is: each one a domain event this spec has no round trip for. */
    private fun <E : Any> covered(root: KClass<E>, samples: List<E>): Set<String> =
        root.sealedSubclasses.map { it.simpleName.orEmpty() }.toSet() - samples.map { it::class.simpleName.orEmpty() }.toSet()
}
