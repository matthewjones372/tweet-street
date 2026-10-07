package checks.monitoring.domain

import checks.policy.domain.{LiveRules, Movement}
import verdict.Evaluator
import zio.schema.codec.fieldNumber
import zio.schema.{DeriveSchema, Schema}

// One account event as Monitoring sees it: where it sits in the account, and the movement it was, if any.
final case class Seen(account: String, sequence: Long, atMillis: Long, movement: Option[Movement])

// The published language on checks.flags. Numbered by hand, as flag.proto beside it is: (account, sequence, rule,
// version) is unique, and a field is only ever added, on a new number.
final case class Flag(
  @fieldNumber(1) account: String,
  @fieldNumber(2) sequence: Long,
  @fieldNumber(3) rule: String,
  @fieldNumber(4) version: Int,
  @fieldNumber(5) severity: String,
  @fieldNumber(6) atMillis: Long,
  @fieldNumber(7) currency: String,
  @fieldNumber(8) amount: String,
  @fieldNumber(9) evidence: String
)

object Flag:
  given Schema[Flag] = DeriveSchema.gen

enum MonitoringError(msg: String) extends RuntimeException(msg):
  case Unavailable(cause: Throwable) extends MonitoringError(s"a movement could not be kept: ${cause.getMessage}")
  case Unpublished(cause: Throwable) extends MonitoringError(s"flags could not be published: ${cause.getMessage}")

object Monitor:
  // Every live monitoring rule that holds on the movement is a flag; an event that moved no money has none.
  def flags(seen: Seen, live: LiveRules): List[Flag] =
    seen.movement.toList.flatMap { movement =>
      live.rules.flatMap { rule =>
        Evaluator
          .evaluate(rule.condition, movement, Movement.rules)
          .toOption
          .filter(_.held)
          .map { evidence =>
            Flag(
              seen.account,
              seen.sequence,
              rule.name,
              rule.version,
              rule.severity.toString.toLowerCase,
              seen.atMillis,
              movement.currency,
              movement.amount.bigDecimal.toPlainString,
              evidence.render().trim
            )
          }
      }
    }
