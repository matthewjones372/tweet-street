package checks.bankevents

import bank.events.v1.*
import checks.platform.Framing
import checks.policy.domain.Movement
import com.google.protobuf.CodedOutputStream
import zio.test.*

import java.io.ByteArrayOutputStream

object MovementsSpec extends ZIOSpecDefault:
  private def gbp(amount: String) = Money.newBuilder.setCurrency("GBP").setAmount(amount).build

  private val base = AccountEvent.newBuilder.setAccountId("acc-1").setSequence(7).setAtMillis(1_700_000_000_000L)

  private val withdrawn =
    base.clone.setWithdrawn(Withdrawn.newBuilder.setAmount(gbp("12.50")).setReference("atm-1")).build

  private def kind(event: AccountEvent) = Movements.from(event).map(_.kind)

  def spec = suite("Movements")(
    test("a record framed as the bank frames it reads back as the movement it was") {
      val record = Framing.frame(42, withdrawn.toByteArray)
      assertTrue(
        Movements.read(record) == Right(Some(Movement("acc-1", "withdrawal", "GBP", BigDecimal("12.50"), "atm-1", 22)))
      )
    },
    test("every account event kind becomes the right movement, or none") {
      val money = gbp("1.00")
      assertTrue(
        kind(base.clone.setDeposited(Deposited.newBuilder.setAmount(money).setReference("r")).build) == Some("deposit"),
        kind(withdrawn) == Some("withdrawal"),
        kind(base.clone.setDebited(Debited.newBuilder.setAmount(money).setTransferId("t")).build) == Some("debit"),
        kind(base.clone.setOpened(Opened.newBuilder.setOwner("ada").setInitial(money)).build).isEmpty,
        kind(base.clone.setCredited(Credited.newBuilder.setAmount(money).setTransferId("t")).build).isEmpty,
        kind(base.clone.setRefunded(Refunded.newBuilder.setAmount(money).setTransferId("t")).build).isEmpty,
        kind(base.clone.setLegsClosed(LegsClosed.newBuilder.setTransferId("t")).build).isEmpty
      )
    },
    test("a debit's reference is its transfer") {
      val debited = base.clone.setDebited(Debited.newBuilder.setAmount(gbp("3.00")).setTransferId("t-9")).build
      assertTrue(Movements.from(debited).map(_.reference) == Some("t-9"))
    },
    test("an event of a kind added after this build passes without a word") {
      // Field 30 of the event oneof: a case the bank may add later, which this build has never heard of.
      val bytes  = ByteArrayOutputStream()
      val output = CodedOutputStream.newInstance(bytes)
      output.writeString(1, "acc-1")
      output.writeInt64(2, 8)
      output.writeBytes(30, com.google.protobuf.ByteString.copyFromUtf8("anything"))
      output.flush()
      val event = AccountEvent.parseFrom(bytes.toByteArray)
      assertTrue(event.getEventCase == AccountEvent.EventCase.EVENT_NOT_SET, Movements.from(event).isEmpty)
    },
    test("a record not framed is refused, not misread") {
      assertTrue(Movements.read(Array[Byte](1, 2, 3, 4, 5, 6, 7)).isLeft)
    }
  )
