package checks.monitoring.adapters

import checks.monitoring.domain.*
import checks.monitoring.service.Movements
import checks.platform.{Queries, ZTransactor}
import checks.policy.domain.{DryRun, Movement}
import verdict.{FieldValue, NamingStrategy, Rule, SqlInterpreter}
import com.augustnagro.magnum.*
import zio.*

import javax.sql.DataSource

final private case class FlagRow(
  account: String,
  sequence: Long,
  rule: String,
  version: Int,
  severity: String,
  atMillis: Long,
  currency: String,
  amount: java.math.BigDecimal,
  evidence: String
) derives DbCodec:
  def toFlag: Flag =
    Flag(account, sequence, rule, version, severity, atMillis, currency, amount.toPlainString, evidence)

final private case class MovementRow(
  account: String,
  kind: String,
  currency: String,
  amount: java.math.BigDecimal,
  reference: String,
  hourOfDay: Int
) derives DbCodec:
  def toMovement: Movement = Movement(account, kind, currency, BigDecimal(amount), reference, hourOfDay)

object PostgresMovements:
  val layer: RLayer[DataSource, Movements] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresMovementsLive(ZTransactor(dataSource)))

final private case class PostgresMovementsLive(transactor: ZTransactor) extends Movements:
  // Seen again after a restart, an event writes nothing twice.
  def keep(seen: Seen, flags: List[Flag]): IO[MonitoringError, Unit] =
    transactor.transact {
      val movement = seen.movement
      val _        =
        sql"""INSERT INTO monitoring.movement
                (account, sequence, kind, currency, amount, reference, hour_of_day, at_millis)
              VALUES (${seen.account}, ${seen.sequence}, ${movement.map(_.kind)}, ${movement.map(_.currency)},
                      ${movement.map(_.amount.bigDecimal)}, ${movement.map(_.reference)},
                      ${movement.map(_.hourOfDay)}, ${seen.atMillis})
              ON CONFLICT (account, sequence) DO NOTHING""".update.run()
      flags.foreach { flag =>
        val _ =
          sql"""INSERT INTO monitoring.flag
                  (account, sequence, rule, version, severity, at_millis, currency, amount, evidence)
                VALUES (${flag.account}, ${flag.sequence}, ${flag.rule}, ${flag.version}, ${flag.severity},
                        ${flag.atMillis}, ${flag.currency}, ${java.math.BigDecimal(flag.amount)}, ${flag.evidence})
                ON CONFLICT (account, sequence, rule, version) DO NOTHING""".update.run()
      }
    }.mapError(MonitoringError.Unavailable(_))

  def recentFlags(limit: Int): IO[MonitoringError, List[Flag]] =
    transactor.connect {
      sql"""SELECT account, sequence, rule, version, severity, at_millis, currency, amount, evidence
              FROM monitoring.flag ORDER BY at_millis DESC LIMIT $limit""".query[FlagRow].run().toList
    }
      .mapBoth(MonitoringError.Unavailable(_), _.map(_.toFlag))

  // Only events that moved money are movements: the rest are kept for their offsets, never matched.
  def dryRun(condition: Rule, sinceMillis: Long): IO[MonitoringError, DryRun] =
    val recent   = s"(SELECT * FROM monitoring.movement WHERE kind IS NOT NULL AND at_millis >= $sinceMillis) AS recent"
    val compiled = SqlInterpreter.toSql(condition, Movement.rules, recent, NamingStrategy.snake)
    val params   = compiled.params.map {
      case FieldValue.Text(text)  => text
      case FieldValue.Num(number) => number
      case FieldValue.Flag(held)  => held
    }
    transactor.connect {
      val connection = summon[DbCon].connection
      DryRun(
        Queries.count(connection, compiled.sql, params),
        Queries.count(connection, s"SELECT * FROM $recent", Nil),
        Queries.strings(connection, compiled.sql, params, "account || ' #' || sequence", 5)
      )
    }
      .mapError(MonitoringError.Unavailable(_))

  def movements(sinceMillis: Long): IO[MonitoringError, List[Movement]] =
    transactor.connect {
      sql"""SELECT account, kind, currency, amount, reference, hour_of_day FROM monitoring.movement
              WHERE kind IS NOT NULL AND at_millis >= $sinceMillis""".query[MovementRow].run().toList
    }
      .mapBoth(MonitoringError.Unavailable(_), _.map(_.toMovement))
