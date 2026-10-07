package checks.policy.service

import checks.policy.domain.*
import verdict.Rule
import zio.*
import zio.metrics.Metric
import zio.stream.*

// The port Policy's store implements. `changes` emits whenever a version may have been stored, by anyone.
trait Versions:
  def insert(
    draft: Draft,
    condition: Rule,
    author: String,
    atMillis: Long,
    approval: Approval
  ): IO[PolicyError, Version]
  def all: IO[PolicyError, List[Version]]
  def changes: ZStream[Any, Nothing, Unit]
  def asked(request: String): IO[PolicyError, Option[Version]]
  def decide(rule: String, number: Int, approval: Approval): IO[PolicyError, Unit]

// Approvals (lark-bank spec 0019), as the check uses it: asked about a version, and told it was made live, or why not.
trait Approvals:
  def ask(proposal: Proposal): IO[PolicyError, (String, String)]
  def applied(request: String, hash: String): IO[PolicyError, Unit]
  def applyFailed(request: String, why: String): IO[PolicyError, Unit]

object Approvals:
  val off: ULayer[Approvals] = ZLayer.succeed(new Approvals:
    private def none                              = ZIO.fail(PolicyError.Unavailable(RuntimeException("approvals are off")))
    def ask(proposal: Proposal)                   = none
    def applied(request: String, hash: String)    = none
    def applyFailed(request: String, why: String) = none)

// What Approvals answered about one request: given, for content with this hash, or ended without it.
enum Decision:
  case Given(request: String, hash: String)
  case Ended(request: String, how: String)

// With `asking`, a version that would change what is in force waits for approval; `link` is the wizard's page for it.
final case class PolicyConfig(
  pollEvery: Duration,
  asking: Boolean = false,
  link: Option[String] = None,
  approvalsPages: Option[String] = None
)

// What Screening and Monitoring see of Policy: the live rules for their subject, and nothing to write with.
trait LiveRulesSource:
  def live(subject: Subject): UIO[LiveRules]

object LiveRulesSource:
  def fixed(rules: LiveRules*): LiveRulesSource =
    val bySubject = rules.map(set => set.subject -> set).toMap
    subject => ZIO.succeed(bySubject.getOrElse(subject, LiveRules(subject, Nil)))

trait Policy extends LiveRulesSource:
  def store(draft: Draft, author: String): IO[PolicyError, Version]
  def propose(draft: Draft, author: String, reach: Option[Reach]): IO[PolicyError, Version]
  def decided(decision: Decision): IO[PolicyError, Unit]
  def versions(rule: String): IO[PolicyError, List[Version]]
  def all: IO[PolicyError, List[Version]]

object Policy:
  def store(draft: Draft, author: String): ZIO[Policy, PolicyError, Version] =
    ZIO.serviceWithZIO[Policy](_.store(draft, author))

  def live(subject: Subject): URIO[Policy, LiveRules] = ZIO.serviceWithZIO[Policy](_.live(subject))

  // The live rules are held in memory and reloaded whenever the store says it changed, or the poll comes round.
  val layer: ZLayer[Versions & Approvals & PolicyConfig, PolicyError, Policy] =
    ZLayer.scoped {
      for
        versions  <- ZIO.service[Versions]
        approvals <- ZIO.service[Approvals]
        config    <- ZIO.service[PolicyConfig]
        current   <- versions.all.flatMap(loaded => Ref.make(bySubject(loaded)))
        reload     = versions.all.flatMap(loaded => current.set(bySubject(loaded)))
        _         <- versions.changes
               .merge(ZStream.tick(config.pollEvery))
               .mapZIO(_ => reload.catchAll(error => ZIO.logWarning(s"the live rules were not reloaded: $error")))
               .runDrain
               .forkScoped
      yield PolicyLive(versions, approvals, config, current)
    }

  private def bySubject(versions: List[Version]): Map[Subject, LiveRules] =
    Subject.values.map(subject => subject -> LiveRules.of(subject, versions)).toMap

final private case class PolicyLive(
  store: Versions,
  approvals: Approvals,
  config: PolicyConfig,
  current: Ref[Map[Subject, LiveRules]]
) extends Policy:
  private def now = Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

  private def counted(version: Version) =
    Metric
      .counter("checks_policy_versions")
      .tagged("rule", version.rule)
      .tagged("status", version.status.toString.toLowerCase)
      .increment
      .as(version)

  def store(draft: Draft, author: String): IO[PolicyError, Version] =
    for
      condition <- ZIO.fromEither(Draft.validate(draft))
      at        <- now
      version   <- store.insert(draft, condition, author, at, Approval.NotNeeded).flatMap(counted)
    yield version

  // A draft changes nothing in force and is stored at once; anything else is asked about first, then stored waiting.
  def propose(draft: Draft, author: String, reach: Option[Reach]): IO[PolicyError, Version] =
    if !config.asking || draft.status == Status.Draft then store(draft, author)
    else
      for
        condition         <- ZIO.fromEither(Draft.validate(draft))
        history           <- store.all.map(_.filter(_.rule == draft.rule))
        live               = history.filter(v => v.status != Status.Draft && v.inForce).maxByOption(_.number)
        number             = history.map(_.number).maxOption.getOrElse(0) + 1
        link               = config.link.map(base => s"$base/#/rules/${draft.rule}")
        proposal           = Proposal.of(draft, condition, number, live.filter(_.status == Status.Live), reach, link, author)
        (request, hashed) <- approvals.ask(proposal)
        _                 <- ZIO.unless(hashed == proposal.contentHash)(
               ZIO.fail(PolicyError.Unavailable(RuntimeException("Approvals hashed the change differently")))
             )
        at      <- now
        version <-
          store.insert(draft, condition, author, at, Approval.Waiting(request, proposal.contentHash)).flatMap(counted)
      yield version

  // At least once: an approval heard again is answered again, and one for content other than what was asked is not
  // made live.
  def decided(decision: Decision): IO[PolicyError, Unit] =
    decision match
      case Decision.Given(request, hash) =>
        // An automatic approval can be heard before the version asked about is stored.
        store
          .asked(request)
          .repeat(Schedule.recurWhile[Option[Version]](_.isEmpty) <* Schedule.spaced(200.millis) <* Schedule.recurs(25))
          .flatMap {
            case Some(v) =>
              v.approval match
                case Approval.Waiting(_, asked) if asked == hash =>
                  store.decide(v.rule, v.number, Approval.Given(request, hash)) *> approvals.applied(request, hash)
                case Approval.Given(_, asked) if asked == hash => approvals.applied(request, hash)
                case _                                         =>
                  approvals.applyFailed(request, s"${v.rule} version ${v.number} is not the content approved")
            case None => ZIO.unit
          }
      case Decision.Ended(request, how) =>
        store.asked(request).flatMap {
          case Some(v) if v.approval.isInstanceOf[Approval.Waiting] =>
            store.decide(v.rule, v.number, Approval.Refused(request, how))
          case _ => ZIO.unit
        }

  def versions(rule: String): IO[PolicyError, List[Version]] =
    store.all.map(_.filter(_.rule == rule).sortBy(_.number)).flatMap { found =>
      if found.isEmpty then ZIO.fail(PolicyError.NoSuchRule(rule)) else ZIO.succeed(found)
    }

  def all: IO[PolicyError, List[Version]] = store.all

  def live(subject: Subject): UIO[LiveRules] = current.get.map(_(subject))
