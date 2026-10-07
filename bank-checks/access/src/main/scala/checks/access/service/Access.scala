package checks.access.service

import checks.access.domain.*
import zio.*

import java.nio.charset.StandardCharsets.US_ASCII
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

// Admins are whoever the identity provider puts in one of `admins` (lark-bank spec 0019): Pocket ID at home.
final case class AccessConfig(admins: Set[String], sessionFor: Duration, signInWithin: Duration)

trait IdentityProvider:
  def authorizeUrl(state: String, nonce: String, challenge: String): IO[AccessError, String]
  def exchange(code: String, verifier: String, nonce: String): IO[AccessError, Signed]

trait Sessions:
  def open(session: Session): IO[AccessError, Unit]
  def find(token: String, nowMillis: Long): IO[AccessError, Option[Session]]
  def close(token: String): IO[AccessError, Unit]
  def begin(pending: Pending): IO[AccessError, Unit]
  def take(state: String, nowMillis: Long): IO[AccessError, Option[Pending]]

trait Access:
  def begin(returnTo: String): IO[AccessError, (String, Pending)]
  def finish(state: String, code: String, sameBrowser: Option[String]): IO[AccessError, (Session, String)]
  def who(token: String): IO[AccessError, Admin]
  def signOut(token: String): IO[AccessError, Unit]

object Access:
  val layer: URLayer[AccessConfig & IdentityProvider & Sessions, Access] = ZLayer.fromFunction(AccessLive.apply)

  // Only a path on this service: anything else could send a person signing in somewhere else.
  def safe(returnTo: String): String =
    if returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.contains('\\') then returnTo else "/"

final private case class AccessLive(config: AccessConfig, provider: IdentityProvider, sessions: Sessions)
    extends Access:
  private val random = SecureRandom()

  private def token: UIO[String] = ZIO.succeed {
    val bytes = Array.ofDim[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private def challenge(verifier: String) =
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII))
    )

  private def now = Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

  def begin(returnTo: String): IO[AccessError, (String, Pending)] =
    for
      state    <- token
      verifier <- token
      nonce    <- token
      at       <- now
      pending   = Pending(state, verifier, nonce, Access.safe(returnTo), at + config.signInWithin.toMillis)
      url      <- provider.authorizeUrl(state, nonce, challenge(verifier))
      _        <- sessions.begin(pending)
    yield (url, pending)

  // The state must come back to the browser that set out with it, and is good once.
  def finish(state: String, code: String, sameBrowser: Option[String]): IO[AccessError, (Session, String)] =
    for
      _       <- ZIO.unless(sameBrowser.contains(state))(ZIO.fail(AccessError.BadSignIn("not begun in this browser")))
      at      <- now
      pending <- sessions.take(state, at).someOrFail(AccessError.BadSignIn("begun too long ago, or already used"))
      signed  <- provider.exchange(code, pending.verifier, pending.nonce)
      _       <- ZIO.unless(signed.groups.exists(config.admins))(ZIO.fail(AccessError.NotAnAdmin(signed.name)))
      session <- token.map(Session(_, Admin(signed.name), at + config.sessionFor.toMillis))
      _       <- sessions.open(session)
    yield (session, pending.returnTo)

  def who(token: String): IO[AccessError, Admin] =
    for
      at      <- now
      session <- sessions.find(token, at).someOrFail(AccessError.NoSession)
    yield session.admin

  def signOut(token: String): IO[AccessError, Unit] = sessions.close(token)
