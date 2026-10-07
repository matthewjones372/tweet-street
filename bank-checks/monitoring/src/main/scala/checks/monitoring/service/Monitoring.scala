package checks.monitoring.service

import checks.monitoring.domain.*
import checks.policy.domain.{DryRun, Movement, Subject}
import checks.policy.service.LiveRulesSource
import verdict.Rule
import zio.*
import zio.metrics.Metric

// The ports Monitoring needs: somewhere to keep what it saw, and somewhere to publish what it flagged.
trait Movements:
  def keep(seen: Seen, flags: List[Flag]): IO[MonitoringError, Unit]
  def recentFlags(limit: Int): IO[MonitoringError, List[Flag]]
  def dryRun(condition: Rule, sinceMillis: Long): IO[MonitoringError, DryRun]
  def movements(sinceMillis: Long): IO[MonitoringError, List[Movement]]

trait FlagSink:
  def publish(flags: List[Flag]): IO[MonitoringError, Unit]

// Monitoring's query side, which the admin reads: never its tables.
trait MonitoringQueries:
  def recentFlags(limit: Int): IO[MonitoringError, List[Flag]]
  def dryRun(condition: Rule, sinceMillis: Long): IO[MonitoringError, DryRun]
  def movements(sinceMillis: Long): IO[MonitoringError, List[Movement]]

trait Monitoring extends MonitoringQueries:
  // Done when the movement is kept and its flags acknowledged: only then may the event's offset be committed.
  def check(seen: Seen): IO[MonitoringError, List[Flag]]

object Monitoring:
  def check(seen: Seen): ZIO[Monitoring, MonitoringError, List[Flag]] = ZIO.serviceWithZIO[Monitoring](_.check(seen))

  val layer: URLayer[LiveRulesSource & Movements & FlagSink, Monitoring & MonitoringQueries] =
    ZLayer
      .fromFunction(MonitoringLive.apply)
      .flatMap(env => ZLayer.succeedEnvironment(env.add[MonitoringQueries](env.get)))

final private case class MonitoringLive(rules: LiveRulesSource, movements: Movements, sink: FlagSink)
    extends Monitoring:
  def check(seen: Seen): IO[MonitoringError, List[Flag]] =
    for
      live <- rules.live(Subject.Movements)
      flags = Monitor.flags(seen, live)
      _    <- movements.keep(seen, flags)
      _    <- sink.publish(flags)
      _    <- Metric.counter("checks_monitoring_events").increment
      _    <-
        ZIO.foreachDiscard(flags)(flag => Metric.counter("checks_monitoring_flags").tagged("rule", flag.rule).increment)
    yield flags

  def recentFlags(limit: Int): IO[MonitoringError, List[Flag]] = movements.recentFlags(limit)

  def dryRun(condition: Rule, sinceMillis: Long): IO[MonitoringError, DryRun] = movements.dryRun(condition, sinceMillis)

  def movements(sinceMillis: Long): IO[MonitoringError, List[Movement]] = movements.movements(sinceMillis)
