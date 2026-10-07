package checks.screening.adapters

import checks.platform.{Queries, ZTransactor}
import checks.policy.domain.{DryRun, ProposedTransfer}
import verdict.{FieldValue, NamingStrategy, Rule, SqlInterpreter}
import checks.screening.domain.*
import checks.screening.service.Decisions
import com.augustnagro.magnum.*
import zio.*

import javax.sql.DataSource

final private case class DecisionRow(
  transfer: String,
  outcome: String,
  rule: Option[String],
  version: Option[Int],
  evidence: String
) derives DbCodec:
  def toDecision: Decision = Decision(transfer, Outcome.valueOf(outcome), rule, version, evidence)

final private case class AskedRow(
  fromAccount: String,
  toAccount: String,
  currency: String,
  amount: java.math.BigDecimal,
  hourOfDay: Int
) derives DbCodec:
  def toProposed: ProposedTransfer = ProposedTransfer(fromAccount, toAccount, currency, BigDecimal(amount), hourOfDay)

object PostgresDecisions:
  // `from` and `to` are SQL's own words, so their columns are named for what they hold.
  private[adapters] val naming = NamingStrategy.withOverrides(Map("from" -> "from_account", "to" -> "to_account"))

  private[adapters] def value(field: FieldValue): Any = field match
    case FieldValue.Text(text)  => text
    case FieldValue.Num(number) => number
    case FieldValue.Flag(held)  => held

  val layer: RLayer[DataSource, Decisions] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresDecisionsLive(ZTransactor(dataSource)))

final private case class PostgresDecisionsLive(transactor: ZTransactor) extends Decisions:
  def keep(asked: Asked, decision: Decision, decidedAtMillis: Long): IO[ScreeningError, Decision] =
    val proposed = asked.proposed
    transactor.connect {
      val inserted =
        sql"""INSERT INTO screening.decision
                  (transfer, outcome, rule, version, evidence, from_account, to_account, currency, amount,
                   hour_of_day, requested_at_millis, decided_at_millis)
                VALUES (${decision.transfer}, ${decision.outcome.toString}, ${decision.rule}, ${decision.version},
                        ${decision.evidence}, ${proposed.from}, ${proposed.to}, ${proposed.currency},
                        ${proposed.amount.bigDecimal}, ${proposed.hourOfDay}, ${asked.requestedAtMillis},
                        $decidedAtMillis)
                ON CONFLICT (transfer) DO NOTHING
                RETURNING transfer, outcome, rule, version, evidence""".query[DecisionRow].run()
      inserted.headOption.getOrElse(
        sql"""SELECT transfer, outcome, rule, version, evidence FROM screening.decision
                WHERE transfer = ${decision.transfer}""".query[DecisionRow].run().head
      )
    }
      .mapBoth(ScreeningError.Unavailable(_), _.toDecision)

  def recent(limit: Int): IO[ScreeningError, List[Decision]] =
    transactor.connect {
      sql"""SELECT transfer, outcome, rule, version, evidence FROM screening.decision
              ORDER BY decided_at_millis DESC LIMIT $limit""".query[DecisionRow].run().toList
    }
      .mapBoth(ScreeningError.Unavailable(_), _.map(_.toDecision))

  def dryRun(condition: Rule, sinceMillis: Long): IO[ScreeningError, DryRun] =
    val recent   = s"(SELECT * FROM screening.decision WHERE decided_at_millis >= $sinceMillis) AS recent"
    val compiled = SqlInterpreter.toSql(condition, ProposedTransfer.rules, recent, PostgresDecisions.naming)
    val params   = compiled.params.map(PostgresDecisions.value)
    transactor.connect {
      val connection = summon[DbCon].connection
      DryRun(
        Queries.count(connection, compiled.sql, params),
        Queries.count(connection, s"SELECT * FROM $recent", Nil),
        Queries.strings(connection, compiled.sql, params, "transfer", 5)
      )
    }
      .mapError(ScreeningError.Unavailable(_))

  def asked(sinceMillis: Long): IO[ScreeningError, List[ProposedTransfer]] =
    transactor.connect {
      sql"""SELECT from_account, to_account, currency, amount, hour_of_day FROM screening.decision
              WHERE decided_at_millis >= $sinceMillis""".query[AskedRow].run().toList
    }
      .mapBoth(ScreeningError.Unavailable(_), _.map(_.toProposed))
