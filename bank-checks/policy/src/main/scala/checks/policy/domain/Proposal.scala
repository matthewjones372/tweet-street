package checks.policy.domain

import verdict.{Analysis, Rule, RuleJson}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

// How far a rule reaches over the records kept for the last `days`: the live version's count, and the new one's.
final case class Reach(before: Long, after: Long, of: Long, days: Int)

// One version asked for, as Approvals shows it (lark-bank spec 0019): the rule as it is in force and as it would be,
// facts its policy is matched on, and the reach beside them.
final case class Proposal(
  subject: String,
  title: String,
  before: String,
  after: String,
  facts: Map[String, String],
  impact: Option[String],
  link: Option[String],
  requester: String
):
  // Approvals' hash of what is approved, made here too: the check makes live only the content it asked about.
  def contentHash: String = Proposal.contentHash(before, after, facts)

object Proposal:
  val kind = "checks.rule-version"

  def contentHash(before: String, after: String, facts: Map[String, String]): String =
    val listed = facts.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString("\n")
    MessageDigest
      .getInstance("SHA-256")
      .digest(s"before\u0000$before\u0000after\u0000$after\u0000facts\u0000$listed".getBytes(UTF_8))
      .map(b => f"$b%02x")
      .mkString

  private def does(subject: Subject) = subject match
    case Subject.Transfers => "declines a transfer"
    case Subject.Movements => "flags a movement"

  // A version as an approver reads it: what it does, when, and where it stands.
  def text(subject: Subject, condition: Rule, severity: Severity, position: Int): String =
    s"${does(subject)} when ${Analysis.describe(condition)}\nseverity: ${severity.toString.toLowerCase}\nposition: $position"

  // What kind of change it is, for the policy's automatic approvals: worked out from the two versions and their reach.
  def change(draft: Draft, condition: Rule, live: Option[Version], reach: Option[Reach]): String =
    if draft.status == Status.Off then "switch-off"
    // A first version, or one after the rule was off, can only stop more than nothing does.
    else if live.isEmpty then "tighten"
    else if live.exists(l => RuleJson.render(l.condition) == RuleJson.render(condition)) then "describe-only"
    else
      reach match
        case Some(r) if r.after > r.before => "tighten"
        case Some(r) if r.after < r.before => "loosen"
        case _                             => "reshape"

  def of(
    draft: Draft,
    condition: Rule,
    number: Int,
    live: Option[Version],
    reach: Option[Reach],
    link: Option[String],
    requester: String
  ): Proposal =
    val before = live.fold("(no version in force)")(l => text(l.subject, l.condition, l.severity, l.position))
    val after  =
      if draft.status == Status.Off then "(switched off)"
      else text(draft.subject, condition, draft.severity, draft.position)
    val counted = reach.map { r =>
      val verb = if draft.subject == Subject.Transfers then "declined" else "flagged"
      s"$verb ${r.before} of ${r.of} in the last ${r.days} days; would have $verb ${r.after}"
    }
    Proposal(
      s"checks/rule/${draft.rule}",
      s"${draft.rule}, version $number${if draft.status == Status.Off then ", switched off" else ""}",
      before,
      after,
      Map("change" -> change(draft, condition, live, reach), "severity" -> draft.severity.toString.toLowerCase),
      counted,
      link,
      requester
    )
