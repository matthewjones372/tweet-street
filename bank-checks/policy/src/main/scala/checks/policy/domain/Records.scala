package checks.policy.domain

import zio.schema.DeriveSchema

// What a rule reads: Policy's published language. Screening is asked about a ProposedTransfer, Monitoring reads a
// Movement, and the wizard offers each record's fields from its verdict schema. The two schemas are givens rather
// than one `derives` clause, which cannot name two type classes called Schema.
final case class ProposedTransfer(from: String, to: String, currency: String, amount: BigDecimal, hourOfDay: Int)

object ProposedTransfer:
  given rules: verdict.Schema[ProposedTransfer]    = verdict.Schema.derived
  given codec: zio.schema.Schema[ProposedTransfer] = DeriveSchema.gen

final case class Movement(
  account: String,
  kind: String,
  currency: String,
  amount: BigDecimal,
  reference: String,
  hourOfDay: Int
)

object Movement:
  given rules: verdict.Schema[Movement]    = verdict.Schema.derived
  given codec: zio.schema.Schema[Movement] = DeriveSchema.gen

// What a rule is about: a screening rule reads proposed transfers, a monitoring rule movements.
enum Subject(val schema: verdict.Schema[?]):
  case Transfers extends Subject(ProposedTransfer.rules)
  case Movements extends Subject(Movement.rules)
