package checks.access.adapters

import bank.issuer.TestIssuer
import checks.access.domain.Signed
import zio.*
import zio.test.*

import java.time.Duration as JDuration
import scala.jdk.CollectionConverters.*

object OidcProviderSpec extends ZIOSpecDefault:
  private def issuer = ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attempt(TestIssuer(0, "127.0.0.1", null, null))))

  private def token(issuer: TestIssuer, groups: List[String], audience: String = "checks-pages", nonce: String = "n1") =
    issuer.token("ada", groups.asJava, audience, "Ada", null, nonce, JDuration.ofMinutes(5))

  def spec = suite("ID tokens")(
    test("one the provider signed for this client, with the sign-in's nonce, names the person and their groups") {
      ZIO.serviceWith[TestIssuer] { issuer =>
        val config = OidcConfig(issuer.getUrl, "checks-pages", "", "http://127.0.0.1/callback")
        val keys   = OidcProvider.keys(s"${issuer.getUrl}/jwks")
        assertTrue(
          OidcProvider.verify(config, keys, token(issuer, List("risk", "ops")), "n1") ==
            Right(Signed("ada", Set("risk", "ops")))
        )
      }
    },
    test("one for another client, for another sign-in, or from another issuer is refused") {
      ZIO.serviceWith[TestIssuer] { issuer =>
        val config = OidcConfig(issuer.getUrl, "checks-pages", "", "http://127.0.0.1/callback")
        val keys   = OidcProvider.keys(s"${issuer.getUrl}/jwks")
        assertTrue(
          OidcProvider.verify(config, keys, token(issuer, List("risk"), audience = "lark-bank"), "n1").isLeft,
          OidcProvider.verify(config, keys, token(issuer, List("risk"), nonce = "n2"), "n1").isLeft,
          OidcProvider
            .verify(config.copy(issuer = "https://id.example.internal"), keys, token(issuer, List("risk")), "n1")
            .isLeft
        )
      }
    }
  ).provideShared(issuer.orDie)
