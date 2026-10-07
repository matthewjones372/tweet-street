package checks.access.adapters

import checks.access.domain.*
import checks.access.service.IdentityProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import zio.*

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import scala.util.Try

// The pages' client at the identity provider: public with PKCE when there is no secret, as at home.
final case class OidcConfig(issuer: String, clientId: String, clientSecret: String, callbackUrl: String)

final case class Discovered(authorize: String, token: String, keys: JWKSource[SecurityContext])

object OidcProvider:
  val layer: URLayer[OidcConfig, IdentityProvider] = ZLayer.fromZIO(
    for
      config     <- ZIO.service[OidcConfig]
      discovered <- Ref.Synchronized.make(Option.empty[Discovered])
    yield OidcProviderLive(config, discovered, HttpClient.newHttpClient())
  )

  private val algorithms = Set(JWSAlgorithm.RS256, JWSAlgorithm.ES256, JWSAlgorithm.PS256, JWSAlgorithm.EdDSA).asJava

  // An ID token's claims, if its signature, issuer, audience, expiry and nonce all hold.
  def verify(
    config: OidcConfig,
    keys: JWKSource[SecurityContext],
    idToken: String,
    nonce: String
  ): Either[String, Signed] =
    val processor = DefaultJWTProcessor[SecurityContext]()
    processor.setJWSKeySelector(JWSVerificationKeySelector(algorithms, keys))
    processor.setJWTClaimsSetVerifier(
      DefaultJWTClaimsVerifier[SecurityContext](
        config.clientId,
        JWTClaimsSet.Builder().issuer(config.issuer).claim("nonce", nonce).build(),
        Set("sub", "exp", "iat").asJava
      )
    )
    Try(processor.process(idToken, null)).toEither.left.map(_.getMessage).map { claims =>
      val groups = Option(claims.getStringListClaim("groups")).map(_.asScala.toSet).getOrElse(Set.empty)
      Signed(Option(claims.getStringClaim("preferred_username")).getOrElse(claims.getSubject), groups)
    }

  def keys(jwksUri: String): JWKSource[SecurityContext] =
    JWKSourceBuilder.create[SecurityContext](URI.create(jwksUri).toURL).build()

final private case class OidcProviderLive(
  config: OidcConfig,
  discovered: Ref.Synchronized[Option[Discovered]],
  http: HttpClient
) extends IdentityProvider:
  private def form(fields: (String, String)*) =
    fields.map((k, v) => s"$k=${URLEncoder.encode(v, UTF_8)}").mkString("&")

  // Asked once it first answers; a provider down at start does not stop the check starting.
  private def discovery: IO[AccessError, Discovered] =
    discovered.modifyZIO {
      case Some(found) => ZIO.succeed(found -> Some(found))
      case None        =>
        ZIO.attemptBlocking {
          val url      = config.issuer.stripSuffix("/") + "/.well-known/openid-configuration"
          val response =
            http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
          if response.statusCode != 200 then throw RuntimeException(s"$url answered ${response.statusCode}")
          val document = JSONObjectUtils.parse(response.body)
          Discovered(
            JSONObjectUtils.getString(document, "authorization_endpoint"),
            JSONObjectUtils.getString(document, "token_endpoint"),
            OidcProvider.keys(JSONObjectUtils.getString(document, "jwks_uri"))
          )
        }
          .mapBoth(AccessError.ProviderDown(_), found => found -> Some(found))
    }

  def authorizeUrl(state: String, nonce: String, challenge: String): IO[AccessError, String] =
    discovery.map(found =>
      found.authorize + "?" + form(
        "response_type"         -> "code",
        "client_id"             -> config.clientId,
        "redirect_uri"          -> config.callbackUrl,
        "scope"                 -> "openid profile email groups",
        "state"                 -> state,
        "nonce"                 -> nonce,
        "code_challenge"        -> challenge,
        "code_challenge_method" -> "S256"
      )
    )

  def exchange(code: String, verifier: String, nonce: String): IO[AccessError, Signed] =
    for
      found <- discovery
      fields = List(
                 "grant_type"    -> "authorization_code",
                 "code"          -> code,
                 "redirect_uri"  -> config.callbackUrl,
                 "client_id"     -> config.clientId,
                 "code_verifier" -> verifier
               ) ++ Option.when(config.clientSecret.nonEmpty)("client_secret" -> config.clientSecret)
      answer <- ZIO
                  .attemptBlocking(
                    http.send(
                      HttpRequest
                        .newBuilder(URI.create(found.token))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(fields*)))
                        .build(),
                      HttpResponse.BodyHandlers.ofString()
                    )
                  )
                  .mapError(AccessError.ProviderDown(_))
      _ <- ZIO.unless(answer.statusCode == 200)(
             ZIO.fail(AccessError.BadSignIn(s"the code was refused: ${answer.statusCode} ${answer.body.take(200)}"))
           )
      idToken <- ZIO
                   .attempt(JSONObjectUtils.getString(JSONObjectUtils.parse(answer.body), "id_token"))
                   .orElseFail(AccessError.BadSignIn("no ID token came back"))
      signed <- ZIO
                  .attemptBlocking(OidcProvider.verify(config, found.keys, idToken, nonce))
                  .orDie
                  .absolve
                  .mapError(AccessError.BadSignIn(_))
    yield signed
