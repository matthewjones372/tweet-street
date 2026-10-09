package checks.platform

import com.augustnagro.magnum
import com.augustnagro.magnum.{DbCon, DbTx, SqlLogger, Transactor}
import zio.*

import javax.sql.DataSource

// As starwars-api's: Magnum's own ZIO module is only a milestone, and this is all of it that is needed.
final class ZTransactor private (private val underlying: Transactor):
  def connect[A](query: DbCon ?=> A): Task[A] =
    ZIO.attemptBlocking(magnum.connect(underlying)(query))

  def transact[A](query: DbTx ?=> A): Task[A] =
    ZIO.attemptBlocking(magnum.transact(underlying)(query))

object ZTransactor:
  def apply(dataSource: DataSource): ZTransactor =
    new ZTransactor(Transactor(dataSource = dataSource, sqlLogger = SqlLogger.Default))

  val layer: URLayer[DataSource, ZTransactor] =
    ZLayer.fromFunction((dataSource: DataSource) => ZTransactor(dataSource))

// Checked by Gauntlet.
