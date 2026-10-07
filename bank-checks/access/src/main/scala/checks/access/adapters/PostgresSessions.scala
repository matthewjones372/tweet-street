package checks.access.adapters

import checks.access.domain.*
import checks.access.service.Sessions
import checks.platform.ZTransactor
import com.augustnagro.magnum.*
import zio.*

import javax.sql.DataSource

final private case class SessionRow(token: String, admin: String, expiresAtMillis: Long) derives DbCodec

final private case class SignInRow(
  state: String,
  verifier: String,
  nonce: String,
  returnTo: String,
  expiresAtMillis: Long
) derives DbCodec

object PostgresSessions:
  val layer: RLayer[DataSource, Sessions] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresSessionsLive(ZTransactor(dataSource)))

final private case class PostgresSessionsLive(transactor: ZTransactor) extends Sessions:
  def open(session: Session): IO[AccessError, Unit] =
    transactor.connect {
      val _ = sql"""INSERT INTO access.session (token, admin, expires_at_millis)
                    VALUES (${session.token}, ${session.admin.name}, ${session.expiresAtMillis})""".update.run()
    }.mapError(AccessError.Unavailable(_))

  def find(token: String, nowMillis: Long): IO[AccessError, Option[Session]] =
    transactor.connect {
      sql"""SELECT token, admin, expires_at_millis FROM access.session
              WHERE token = $token AND expires_at_millis > $nowMillis""".query[SessionRow].run().headOption
    }
      .mapBoth(AccessError.Unavailable(_), _.map(row => Session(row.token, Admin(row.admin), row.expiresAtMillis)))

  def close(token: String): IO[AccessError, Unit] =
    transactor.connect {
      val _ = sql"DELETE FROM access.session WHERE token = $token".update.run()
    }.mapError(AccessError.Unavailable(_))

  def begin(pending: Pending): IO[AccessError, Unit] =
    transactor.connect {
      val _ = sql"""INSERT INTO access.sign_in (state, verifier, nonce, return_to, expires_at_millis)
                    VALUES (${pending.state}, ${pending.verifier}, ${pending.nonce}, ${pending.returnTo},
                            ${pending.expiresAtMillis})""".update.run()
    }.mapError(AccessError.Unavailable(_))

  // Deleted as it is read, so a code replayed with the same state finds nothing.
  def take(state: String, nowMillis: Long): IO[AccessError, Option[Pending]] =
    transactor.connect {
      sql"""DELETE FROM access.sign_in WHERE state = $state
              RETURNING state, verifier, nonce, return_to, expires_at_millis""".query[SignInRow].run().headOption
    }
      .mapBoth(
        AccessError.Unavailable(_),
        _.filter(_.expiresAtMillis > nowMillis)
          .map(row => Pending(row.state, row.verifier, row.nonce, row.returnTo, row.expiresAtMillis))
      )
