package bank.protocol

import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Money
import bank.domain.TransferEvent
import bank.v1.Bank
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Bank spec 0005: schema/bank.proto is what the wire shapes make, pinned so a change to what is stored or sent shows
 * in review, and protoc compiles it for this test, so a reader in another language reads what the bank writes.
 */
class SchemaSpec {

    @Test
    fun `the pinned schema is what the wire shapes make`() {
        withClue("a wire shape changed: if that is meant, write bankProto out to protocol/schema/bank.proto") {
            bankProto shouldBe File("schema/bank.proto").readText()
        }
    }

    @Test
    fun `what the journal holds, protoc's classes read once past its version mark`() {
        // The journal marks each event with its version (lark spec 0091); bank.proto describes what follows the mark.
        val bytes = AccountEventBody.encode(AccountEvent.Withdrawn(gbp(500), "w-1", 3))
        val withdrawn = Bank.AccountEvent.parseFrom(bytes)
        withdrawn.hasWithdrawn() shouldBe true
        withdrawn.withdrawn.amount.amount shouldBe "5.00"
        withdrawn.withdrawn.amount.currency.code shouldBe "GBP"
        withdrawn.withdrawn.reference shouldBe "w-1"
    }

    @Test
    fun `what protoc's classes write, the bank reads`() {
        val requested = Bank.TransferEvent.newBuilder()
            .setRequested(
                Bank.Requested.newBuilder().setFrom("a").setTo("b").setAtMillis(1)
                    .setAmount(Bank.Money.newBuilder().setAmount("0.00000009").setCurrency(Bank.Currency.newBuilder().setCode("BTC").setExponent(8))),
            )
            .build()
            .toByteArray()
        TransferEvents.decode(requested) shouldBe TransferEvent.Requested(AccountId("a"), AccountId("b"), Money.ofMinor(9, BTC), 1)
    }
}
