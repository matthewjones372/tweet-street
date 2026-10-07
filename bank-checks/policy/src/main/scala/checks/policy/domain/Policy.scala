package checks.policy.domain

import verdict.{Rule, RuleJson}

enum Severity:
  case Low, Medium, High

// A version is Live (the rule in force), Off (the rule switched off) or a Draft (stored, never in force). The latest
// version that is not a draft says whether the rule is live.
enum Status:
  case Live, Draft, Off

enum PolicyError(msg: String) extends RuntimeException(msg):
  case Invalid(errors: List[String]) extends PolicyError(errors.mkString("; "))
  case BadName(name: String)         extends PolicyError(s"'$name' is not a rule name: lower case, digits and dashes")
  case SubjectChanged(rule: String, was: Subject, now: Subject)
      extends PolicyError(s"$rule is a rule over ${was.toString.toLowerCase}, and stays one")
  case NoSuchRule(name: String)      extends PolicyError(s"no rule $name")
  case Unavailable(cause: Throwable) extends PolicyError(s"the policy's store failed: ${cause.getMessage}")

final case class Draft(
  rule: String,
  subject: Subject,
  document: String,
  severity: Severity,
  position: Int,
  status: Status
)

object Draft:
  private val name = "[a-z0-9][a-z0-9-]{0,63}".r

  // Every error, not the first: the wizard puts each against its row.
  def validate(draft: Draft): Either[PolicyError, Rule] =
    if !name.matches(draft.rule) then Left(PolicyError.BadName(draft.rule))
    else
      RuleJson.load(draft.document, draft.subject.schema).left.map(errors => PolicyError.Invalid(errors.map(_.message)))

// Whether a version waits on others before it is in force (lark-bank spec 0019). One stored while approvals are off
// needs nobody; one asked for is in force once Approvals gives it, and never if its request ends another way.
enum Approval:
  case NotNeeded
  case Waiting(request: String, hash: String)
  case Given(request: String, hash: String)
  case Refused(request: String, how: String)

final case class Version(
  rule: String,
  number: Int,
  subject: Subject,
  condition: Rule,
  severity: Severity,
  position: Int,
  status: Status,
  author: String,
  atMillis: Long,
  approval: Approval = Approval.NotNeeded
):
  def inForce: Boolean = approval match
    case Approval.NotNeeded | Approval.Given(_, _)       => true
    case Approval.Waiting(_, _) | Approval.Refused(_, _) => false

final case class LiveRule(name: String, version: Int, severity: Severity, position: Int, condition: Rule)

// A subject's live rules, in the admin's order: for screening, the first that holds declines.
final case class LiveRules(subject: Subject, rules: List[LiveRule])

object LiveRules:
  def of(subject: Subject, versions: List[Version]): LiveRules =
    val live = versions
      .filter(version => version.subject == subject && version.status != Status.Draft && version.inForce)
      .groupBy(_.rule)
      .values
      .map(_.maxBy(_.number))
      .filter(_.status == Status.Live)
      .map(version => LiveRule(version.rule, version.number, version.severity, version.position, version.condition))
      .toList
      .sortBy(rule => (rule.position, rule.name))
    LiveRules(subject, live)

// What a draft would have done: of the records kept since then, how many it holds on, with a few of them.
final case class DryRun(matched: Long, of: Long, examples: List[String])
