package checks.screening.service

import checks.policy.domain.{DryRun, ProposedTransfer, Subject}
import checks.policy.service.LiveRulesSource
import checks.screening.domain.*
import verdict.Rule
import zio.*
import zio.metrics.{Metric, MetricKeyType}

// The port Screening's store implements. `keep` stores a decision unless the transfer has one, and answers the one
// stored: the first, whoever asked.
trait Decisions:
  def keep(asked: Asked, decision: Decision, decidedAtMillis: Long): IO[ScreeningError, Decision]
  def recent(limit: Int): IO[ScreeningError, List[Decision]]
  def dryRun(condition: Rule, sinceMillis: Long): IO[ScreeningError, DryRun]
  def asked(sinceMillis: Long): IO[ScreeningError, List[ProposedTransfer]]

// Screening's query side, which the admin reads: never its tables.
trait ScreeningQueries:
  def preview(asked: Asked): UIO[Preview]
  def recent(limit: Int): IO[ScreeningError, List[Decision]]
  def dryRun(condition: Rule, sinceMillis: Long): IO[ScreeningError, DryRun]
  def asked(sinceMillis: Long): IO[ScreeningError, List[ProposedTransfer]]

trait Screening extends ScreeningQueries:
  def screen(asked: Asked): IO[ScreeningError, Decision]

object Screening:
  def screen(asked: Asked): ZIO[Screening, ScreeningError, Decision] = ZIO.serviceWithZIO[Screening](_.screen(asked))

  val layer: URLayer[LiveRulesSource & Decisions, Screening & ScreeningQueries] =
    ZLayer
      .fromFunction(ScreeningLive.apply)
      .flatMap(env => ZLayer.succeedEnvironment(env.add[ScreeningQueries](env.get)))

object ScreeningMetrics:
  // In milliseconds: the bank waits 300, and alerts when p99 passes half of it.
  val duration = Metric.histogram(
    "checks_screening_duration_ms",
    MetricKeyType.Histogram.Boundaries.fromChunk(Chunk(1.0, 2, 5, 10, 20, 50, 100, 150, 300, 1000))
  )

  def decided(decision: Decision) =
    Metric
      .counter("checks_screening_decisions")
      .tagged("outcome", decision.outcome.toString.toLowerCase)
      .tagged("rule", decision.rule.getOrElse(""))
      .increment

final private case class ScreeningLive(rules: LiveRulesSource, decisions: Decisions) extends Screening:
  def screen(asked: Asked): IO[ScreeningError, Decision] =
    (for
      live     <- rules.live(Subject.Transfers)
      now      <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      decision <- decisions.keep(asked, Screen.decide(asked, live), now)
      _        <- ScreeningMetrics.decided(decision)
    yield decision).timed.flatMap { (took, decision) =>
      ScreeningMetrics.duration.update(took.toNanos / 1e6).as(decision)
    }

  def preview(asked: Asked): UIO[Preview] = rules.live(Subject.Transfers).map(Screen.preview(asked, _))

  def recent(limit: Int): IO[ScreeningError, List[Decision]] = decisions.recent(limit)

  def dryRun(condition: Rule, sinceMillis: Long): IO[ScreeningError, DryRun] = decisions.dryRun(condition, sinceMillis)

  def asked(sinceMillis: Long): IO[ScreeningError, List[ProposedTransfer]] = decisions.asked(sinceMillis)
