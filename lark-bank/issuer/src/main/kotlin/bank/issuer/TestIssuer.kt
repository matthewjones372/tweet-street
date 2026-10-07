package bank.issuer

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A test identity provider: discovery, keys, a sign-in page where anyone may say who they are and which groups they
 * are in, and a token endpoint. Its keys are made when it starts and never kept.
 *
 * [browserUrl] is where a browser reaches it, when that is not where the bank does: compose's `issuer:9000` from the
 * bank, `localhost:9000` from a laptop's browser. Tokens always name [url].
 */
class TestIssuer(
    port: Int = 0,
    host: String = "127.0.0.1",
    publicUrl: String? = null,
    private val browserUrl: String? = null,
) : AutoCloseable {

    private class Pending(val challenge: String, val nonce: String?, val clientId: String, val redirectUri: String, val who: Who)

    private class Who(val subject: String, val name: String, val groups: List<String>)

    private val key: RSAKey = RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val server = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        executor = Executors.newVirtualThreadPerTaskExecutor()
    }

    val url: String = publicUrl ?: "http://$host:${server.address.port}"

    init {
        server.createContext("/.well-known/openid-configuration") { it.answer(200, JSON, discovery()) }
        server.createContext("/jwks") { it.answer(200, JSON, JWKSet(key.toPublicJWK()).toString()) }
        server.createContext("/authorize") { it.authorize() }
        server.createContext("/approve") { it.approve() }
        server.createContext("/token") { it.token() }
        server.start()
    }

    /** A signed token for [subject], as the provider would issue one to a client for [audience]. */
    fun token(
        subject: String,
        groups: Collection<String> = emptyList(),
        audience: String = "lark-bank",
        name: String = subject,
        actor: String? = null,
        nonce: String? = null,
        lifetime: Duration = Duration.ofHours(12),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(url).subject(subject).audience(audience)
            .issueTime(Date()).expirationTime(Date.from(Instant.now().plus(lifetime)))
            .claim("name", name).claim("groups", groups.toList())
            .apply { if (actor != null) claim("act", mapOf("sub" to actor)) }
            .apply { if (nonce != null) claim("nonce", nonce) }
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }

    private fun discovery(): String {
        val browser = browserUrl ?: url
        return """{"issuer":"$url","jwks_uri":"$url/jwks","authorization_endpoint":"$browser/authorize",""" +
            """"token_endpoint":"$url/token","response_types_supported":["code"],""" +
            """"code_challenge_methods_supported":["S256"],"id_token_signing_alg_values_supported":["RS256"]}"""
    }

    /** A form: who you are, and your groups. Whatever is typed is believed; that is the point of it, and the danger. */
    private fun HttpExchange.authorize() {
        val q = form(requestURI.rawQuery)
        val carried = q.entries.joinToString("") { (k, v) -> """<input type="hidden" name="${escape(k)}" value="${escape(v)}">""" }
        answer(
            200, "text/html; charset=utf-8",
            """<!doctype html><meta charset="utf-8"><title>Test issuer</title>
            <h1>Sign in to the test issuer</h1>
            <form method="post" action="approve">$carried
              <label>Who <input id="subject" name="subject" value="${escape(q["login_hint"] ?: "ada")}"></label>
              <label>Name <input id="name" name="name" value=""></label>
              <label>Groups <input id="groups" name="groups" value="" placeholder="ops, auditor"></label>
              <button id="sign-in" type="submit">Sign in</button>
            </form>""",
        )
    }

    private fun HttpExchange.approve() {
        val f = form(requestBody.readAllBytes().decodeToString())
        val subject = f["subject"].orEmpty().ifBlank { "ada" }
        val who = Who(subject, f["name"].orEmpty().ifBlank { subject }, f["groups"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty))
        val code = UUID.randomUUID().toString()
        pending[code] = Pending(
            f.getValue("code_challenge"), f["nonce"], f.getValue("client_id"), f.getValue("redirect_uri"), who,
        )
        responseHeaders.add("Location", "${f["redirect_uri"]}?code=$code&state=${URLEncoder.encode(f["state"].orEmpty(), Charsets.UTF_8)}")
        sendResponseHeaders(302, -1)
        close()
    }

    /** The authorization-code grant, PKCE checked; and a test grant that hands the load test a token for anyone. */
    private fun HttpExchange.token() {
        val f = form(requestBody.readAllBytes().decodeToString())
        when (f["grant_type"]) {
            "authorization_code" -> {
                val waiting = pending.remove(f["code"])
                val proof = f["code_verifier"]?.let { verifier ->
                    Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())).toString()
                }
                if (waiting == null || proof != waiting.challenge || f["redirect_uri"] != waiting.redirectUri) {
                    return answer(400, JSON, """{"error":"invalid_grant"}""")
                }
                val idToken = token(waiting.who.subject, waiting.who.groups, waiting.clientId, waiting.who.name, nonce = waiting.nonce)
                answer(200, JSON, """{"id_token":"$idToken","token_type":"Bearer"}""")
            }
            TEST_GRANT -> {
                val subject = f["subject"] ?: return answer(400, JSON, """{"error":"invalid_request"}""")
                val groups = f["groups"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
                answer(200, JSON, """{"access_token":"${token(subject, groups, f["audience"] ?: "lark-bank")}","token_type":"Bearer"}""")
            }
            else -> answer(400, JSON, """{"error":"unsupported_grant_type"}""")
        }
    }

    private fun form(text: String?): Map<String, String> =
        text.orEmpty().split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun HttpExchange.answer(status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    companion object {
        /** The grant the load test asks for a token with. Nothing but this issuer answers it. */
        const val TEST_GRANT = "urn:lark-bank:test-token"
        private const val JSON = "application/json"
    }
}

/** Compose's and kind's test issuer: ISSUER_PORT, ISSUER_URL as the bank reaches it, ISSUER_BROWSER_URL as a browser does. */
fun main() {
    val env = System.getenv()
    val issuer = TestIssuer(
        port = env["ISSUER_PORT"]?.toInt() ?: 9000,
        host = "0.0.0.0",
        publicUrl = env["ISSUER_URL"],
        browserUrl = env["ISSUER_BROWSER_URL"],
    )
    println("test issuer at ${issuer.url}")
    Thread.currentThread().join()
}
