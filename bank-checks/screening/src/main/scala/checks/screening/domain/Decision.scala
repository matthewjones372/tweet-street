package checks.screening.domain

import checks.policy.domain.{LiveRules, ProposedTransfer}
import verdict.{Evaluator, Evidence}

import java.time.{Instant, ZoneOffset}

enum Outcome:
  case Approved, Declined

final case class Decision(
  transfer: String,
  outcome: Outcome,
  rule: Option[String],
  version: Option[Int],
  evidence: String
)

// A proposed transfer as the bank asks about it: its id, and when it was requested.
final case class Asked(transfer: String, proposed: ProposedTransfer, requestedAtMillis: Long)

object Asked:
  def apply(
    transfer: String,
    from: String,
    to: String,
    currency: String,
    amount: BigDecimal,
    requestedAtMillis: Long
  ): Asked =
    val hour = Instant.ofEpochMilli(requestedAtMillis).atZone(ZoneOffset.UTC).getHour
    Asked(transfer, ProposedTransfer(from, to, currency, amount, hour), requestedAtMillis)

// One live rule, weighed on a transfer: whether it holds, and why.
final case class Weighed(rule: String, version: Int, held: Boolean, evidence: String)

// What the check would decide for a transfer now, and every live rule's part in it: stored nowhere.
final case class Preview(decision: Decision, rules: List[Weighed])

enum ScreeningError(msg: String) extends RuntimeException(msg):
  case Unavailable(cause: Throwable) extends ScreeningError(s"decisions could not be stored: ${cause.getMessage}")

object Screen:
  // The live rules are the declining ones: the first in the admin's order that holds declines, with its evidence.
  def decide(asked: Asked, live: LiveRules): Decision =
    val held = live.rules.iterator.flatMap { rule =>
      Evaluator
        .evaluate(rule.condition, asked.proposed, ProposedTransfer.rules)
        .toOption
        .filter(_.held)
        .map(rule -> _)
    }
    held.nextOption() match
      case Some((rule, evidence)) =>
        Decision(asked.transfer, Outcome.Declined, Some(rule.name), Some(rule.version), render(evidence))
      case None =>
        val evidence =
          if live.rules.isEmpty then "no screening rule is live"
          else live.rules.map(rule => s"${rule.name} (version ${rule.version}) did not hold").mkString("; ")
        Decision(asked.transfer, Outcome.Approved, None, None, evidence)

  def preview(asked: Asked, live: LiveRules): Preview =
    val weighed = live.rules.map { rule =>
      Evaluator.evaluate(rule.condition, asked.proposed, ProposedTransfer.rules) match
        case Right(evidence) => Weighed(rule.name, rule.version, evidence.held, render(evidence))
        case Left(error)     => Weighed(rule.name, rule.version, held = false, error.message)
    }
    Preview(decide(asked, live), weighed)

  private def render(evidence: Evidence): String = evidence.render().trim
