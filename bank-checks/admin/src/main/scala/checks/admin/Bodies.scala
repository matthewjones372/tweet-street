package checks.admin

import checks.policy.domain.{Movement, ProposedTransfer}
import zio.schema.{DeriveSchema, Schema}

final case class FieldBody(name: String, `type`: String, comparisons: List[String])
object FieldBody:
  given Schema[FieldBody] = DeriveSchema.gen

final case class RecordBody(record: String, fields: List[FieldBody])
object RecordBody:
  given Schema[RecordBody] = DeriveSchema.gen

final case class RuleSummary(
  name: String,
  record: String,
  live: Option[Int],
  latest: Int,
  status: String,
  severity: String,
  position: Int
)
object RuleSummary:
  given Schema[RuleSummary] = DeriveSchema.gen

final case class VersionBody(
  number: Int,
  status: String,
  severity: String,
  position: Int,
  author: String,
  atMillis: Long,
  words: String,
  document: String,
  // Where its approval stands (lark-bank spec 0019): not-needed, waiting, given or refused, and the request.
  approval: String = "not-needed",
  request: Option[String] = None,
  approvalNote: Option[String] = None
)
object VersionBody:
  given Schema[VersionBody] = DeriveSchema.gen

final case class RuleBody(name: String, record: String, versions: List[VersionBody])
object RuleBody:
  given Schema[RuleBody] = DeriveSchema.gen

// A draft as the wizard writes it: the record it reads, and the rule as verdict's JSON.
final case class DraftBody(record: String, document: String)
object DraftBody:
  given Schema[DraftBody] = DeriveSchema.gen

final case class Checked(errors: List[String], words: Option[String], simplest: Option[String])
object Checked:
  given Schema[Checked] = DeriveSchema.gen

final case class TryBody(
  record: String,
  document: String,
  transfer: Option[ProposedTransfer],
  movement: Option[Movement]
)
object TryBody:
  given Schema[TryBody] = DeriveSchema.gen

final case class Tried(held: Boolean, evidence: String)
object Tried:
  given Schema[Tried] = DeriveSchema.gen

final case class DryRunBody(record: String, document: String, days: Option[Int])
object DryRunBody:
  given Schema[DryRunBody] = DeriveSchema.gen

final case class DryRunResult(matched: Long, of: Long, share: Double, examples: List[String])
object DryRunResult:
  given Schema[DryRunResult] = DeriveSchema.gen

final case class NewVersion(record: String, document: String, severity: String, position: Int, status: String)
object NewVersion:
  given Schema[NewVersion] = DeriveSchema.gen

final case class DecisionView(
  transfer: String,
  outcome: String,
  rule: Option[String],
  version: Option[Int],
  evidence: String
)
object DecisionView:
  given Schema[DecisionView] = DeriveSchema.gen

final case class FlagView(
  account: String,
  sequence: Long,
  rule: String,
  version: Int,
  severity: String,
  atMillis: Long,
  currency: String,
  amount: String,
  evidence: String
)
object FlagView:
  given Schema[FlagView] = DeriveSchema.gen

final case class RuleWeighed(rule: String, version: Int, held: Boolean, evidence: String)
object RuleWeighed:
  given Schema[RuleWeighed] = DeriveSchema.gen

final case class Tested(outcome: String, rule: Option[String], version: Option[Int], rules: List[RuleWeighed])
object Tested:
  given Schema[Tested] = DeriveSchema.gen

// Who is signed in, and where Approvals' pages are, for the wizard's links to a version's request.
final case class Who(name: String, approvalsPages: Option[String] = None)
object Who:
  given Schema[Who] = DeriveSchema.gen

// Each failure an admin endpoint declares, with its own status.
enum Problem:
  case NotSignedIn(message: String)
  case Invalid(errors: List[String])
  case NotFound(message: String)
  case Unavailable(message: String)

object Problem:
  given Schema[Problem.NotSignedIn] = DeriveSchema.gen
  given Schema[Problem.Invalid]     = DeriveSchema.gen
  given Schema[Problem.NotFound]    = DeriveSchema.gen
  given Schema[Problem.Unavailable] = DeriveSchema.gen
