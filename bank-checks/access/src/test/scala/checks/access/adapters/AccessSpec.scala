package checks.access.adapters

import checks.access.domain.*
import checks.access.service.*
import checks.platform.TestPostgres
import zio.*
import zio.test.*

object AccessSpec extends ZIOSpecDefault:
  // The provider as a test needs it: the code names who signs in.
  private val provider: ULayer[IdentityProvider] = ZLayer.succeed(new IdentityProvider:
    def authorizeUrl(state: String, nonce: String, challenge: String): IO[AccessError, String] =
      ZIO.succeed(s"https://id.test/authorize?state=$state&challenge=$challenge")
    def exchange(code: String, verifier: String, nonce: String): IO[AccessError, Signed] = code match
      case "ada" => ZIO.succeed(Signed("ada", Set("risk")))
      case "eve" => ZIO.succeed(Signed("eve", Set("marketing")))
      case _     => ZIO.fail(AccessError.BadSignIn("no such code")))

  private val access =
    ZLayer.succeed(AccessConfig(Set("risk", "admins"), 12.hours, 10.minutes)) ++ provider ++ PostgresSessions.layer >>>
      Access.layer

  private def signIn(code: String, returnTo: String = "/") =
    for
      (_, pending) <- ZIO.serviceWithZIO[Access](_.begin(returnTo))
      finished     <- ZIO.serviceWithZIO[Access](_.finish(pending.state, code, Some(pending.state)))
    yield finished

  def spec = suite("Access")(
    test("someone the provider puts in an admins' group signs in, back where they were, and their session says who") {
      for
        (url, _)            <- ZIO.serviceWithZIO[Access](_.begin("/#/rules/large"))
        (session, returnTo) <- signIn("ada", "/#/rules/large")
        admin               <- ZIO.serviceWithZIO[Access](_.who(session.token))
      yield assertTrue(admin == Admin("ada"), returnTo == "/#/rules/large", url.contains("challenge="))
    },
    test("someone in none of the admins' groups is refused, and given no session") {
      for refused <- signIn("eve").flip
      yield assertTrue(refused == AccessError.NotAnAdmin("eve"))
    },
    test("a sign-in finished in another browser, finished twice, or finished too late is refused") {
      for
        (_, first)  <- ZIO.serviceWithZIO[Access](_.begin("/"))
        elsewhere   <- ZIO.serviceWithZIO[Access](_.finish(first.state, "ada", None)).flip
        (_, second) <- ZIO.serviceWithZIO[Access](_.begin("/"))
        _           <- ZIO.serviceWithZIO[Access](_.finish(second.state, "ada", Some(second.state)))
        again       <- ZIO.serviceWithZIO[Access](_.finish(second.state, "ada", Some(second.state))).flip
        (_, third)  <- ZIO.serviceWithZIO[Access](_.begin("/"))
        _           <- TestClock.adjust(11.minutes)
        late        <- ZIO.serviceWithZIO[Access](_.finish(third.state, "ada", Some(third.state))).flip
      yield assertTrue(
        elsewhere.isInstanceOf[AccessError.BadSignIn],
        again.isInstanceOf[AccessError.BadSignIn],
        late.isInstanceOf[AccessError.BadSignIn]
      )
    },
    test("a sign-in only ever returns to a page of the check") {
      for
        (_, away) <- signIn("ada", "https://elsewhere.example/")
        (_, also) <- signIn("ada", "//elsewhere.example/")
      yield assertTrue(away == "/", also == "/")
    },
    test("a session signed out of, or never opened, says nobody") {
      for
        (session, _) <- signIn("ada")
        _            <- ZIO.serviceWithZIO[Access](_.signOut(session.token))
        ended        <- ZIO.serviceWithZIO[Access](_.who(session.token)).flip
        never        <- ZIO.serviceWithZIO[Access](_.who("made-up")).flip
      yield assertTrue(ended == AccessError.NoSession, never == AccessError.NoSession)
    }
  ).provideShared(TestPostgres.migrated("access").orDie >>> access.orDie) @@ TestAspect.sequential
