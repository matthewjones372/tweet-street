package checks.bankevents

import bank.events.v1.AccountEvent
import bank.events.v1.AccountEvent.EventCase
import checks.policy.domain.Movement

import checks.platform.Framing
import java.time.{Instant, ZoneOffset}

// The anti-corruption layer: an account event in, Monitoring's Movement out, and no generated class past here.
// Money in or out of an account by its own hand or a transfer's debit is a movement; the rest, and any case this
// build does not know, pass without a word.
object Movements:
  def from(event: AccountEvent): Option[Movement] =
    event.getEventCase match
      case EventCase.DEPOSITED =>
        val deposited = event.getDeposited
        Some(movement(event, "deposit", deposited.getAmount, deposited.getReference))
      case EventCase.WITHDRAWN =>
        val withdrawn = event.getWithdrawn
        Some(movement(event, "withdrawal", withdrawn.getAmount, withdrawn.getReference))
      case EventCase.DEBITED =>
        val debited = event.getDebited
        Some(movement(event, "debit", debited.getAmount, debited.getTransferId))
      case EventCase.OPENED | EventCase.CREDITED | EventCase.REFUNDED | EventCase.LEGS_CLOSED |
          EventCase.EVENT_NOT_SET =>
        None

  private def movement(event: AccountEvent, kind: String, amount: bank.events.v1.Money, reference: String) =
    Movement(
      account = event.getAccountId,
      kind = kind,
      currency = amount.getCurrency,
      amount = BigDecimal(amount.getAmount),
      reference = reference,
      hourOfDay = Instant.ofEpochMilli(event.getAtMillis).atZone(ZoneOffset.UTC).getHour
    )

  def read(record: Array[Byte]): Either[Throwable, Option[Movement]] =
    Framing.unframe(record).flatMap { framed =>
      scala.util.Try(AccountEvent.parseFrom(framed.message)).toEither.map(from)
    }
