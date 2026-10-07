package checks.screening.adapters

import checks.screening.domain.*
import checks.screening.service.Screening
import zio.*
import zio.http.*
import zio.http.endpoint.Endpoint
import zio.schema.{DeriveSchema, Schema}

// Screening's open host service: the bank's `POST /screen`, in its words (lark-bank's bank.api.Screening).
final case class Amount(value: String, currency: String)
object Amount:
  given Schema[Amount] = DeriveSchema.gen

final case class ProposedTransferBody(
  transfer: String,
  from: String,
  to: String,
  amount: Amount,
  requestedAtMillis: Long
)
object ProposedTransferBody:
  given Schema[ProposedTransferBody] = DeriveSchema.gen

final case class DecisionBody(outcome: String, rule: Option[String], version: Option[Int], evidence: String)
object DecisionBody:
  given Schema[DecisionBody] = DeriveSchema.gen

  def of(decision: Decision): DecisionBody =
    DecisionBody(decision.outcome.toString.toLowerCase, decision.rule, decision.version, decision.evidence)

final case class Refused(message: String)
object Refused:
  given Schema[Refused] = DeriveSchema.gen

object ScreeningHttp:
  val screen =
    Endpoint(RoutePattern.POST / "screen")
      .in[ProposedTransferBody]
      .out[DecisionBody]
      .outError[Refused](Status.UnprocessableEntity)
      ?? zio.http.codec.Doc.p("Decide, once and for good, whether a proposed transfer may move")

  private def asked(body: ProposedTransferBody): Either[Refused, Asked] =
    scala.util
      .Try(BigDecimal(body.amount.value))
      .toOption
      .toRight(Refused(s"${body.amount.value} is not an amount"))
      .map(amount => Asked(body.transfer, body.from, body.to, body.amount.currency, amount, body.requestedAtMillis))

  // A decision that could not be stored is not given: the bank's timer decides instead.
  val routes: Routes[Screening, Nothing] =
    Routes(screen.implement { body =>
      ZIO.fromEither(asked(body)).flatMap(Screening.screen(_).orDie).map(DecisionBody.of)
    })
