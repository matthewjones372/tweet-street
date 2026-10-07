package bank.app

import bank.api.Signing
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Credentials
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.PageGuard
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.oidc.oidc
import io.github.matthewjones372.pelican.oidc.signIn
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * The bank's identity provider as pelican-oidc sees it (bank spec 0021): API tokens verified against [IdentitySettings.issuer],
 * and the pages signed in to as its client, the session sealed with the key every node shares.
 */
fun signing(settings: IdentitySettings): Signing {
    val provider = oidc(settings.issuer, settings.audience)
    val signIn = provider.signIn(
        clientId = settings.clientId,
        callbackUrl = settings.callbackUrl,
        sessionKey = sessionKey(settings.sessionKey),
        clientSecret = settings.clientSecret,
    )
    return object : Signing {
        override val endpoints: List<ServerEndpoint> = signIn.endpoints
        override val authenticator: Authenticator = Verified(signIn.authenticator)
        override val guard: PageGuard = signIn.guard

        override fun actAs(cookies: String?, subject: String, until: Instant): String? =
            signIn.actAs(Cookies(cookies), subject, until)

        override fun stopActing(cookies: String?): String? = signIn.stopActing(Cookies(cookies))
    }
}

/**
 * A bearer token verified once and remembered until it expires: checking its signature costs 70 µs of CPU, every
 * request, and a client sends the same token for as long as it lasts. Keyed by the whole token, so only the same
 * bytes are taken as verified; a JWT cannot be withdrawn before it expires, so remembering one changes nothing about
 * what is accepted. Sessions and refusals are not remembered.
 */
private class Verified(private val underneath: Authenticator, private val most: Int = 10_000) : Authenticator {
    private class Known(val identity: Identity, val until: Instant)

    private val known = ConcurrentHashMap<String, Known>()

    override fun authenticate(credentials: Credentials): Identity? {
        val token = credentials.bearerToken() ?: return underneath.authenticate(credentials)
        val now = Instant.now()
        known[token]?.takeIf { now.isBefore(it.until) }?.let { return it.identity }
        val identity = underneath.authenticate(credentials) ?: return null
        val expires = (identity.claims["exp"] as? Date)?.toInstant() ?: return identity
        if (known.size >= most) known.clear()
        known[token] = Known(identity, expires)
        return identity
    }
}

/** A request's `Cookie` header as the credentials pelican-oidc reads a session from. */
private class Cookies(private val header: String?) : Credentials {
    override fun header(name: String): String? = if (name.equals("Cookie", ignoreCase = true)) header else null

    override fun cookie(name: String): String? = header.orEmpty().split(';').map(String::trim)
        .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

private fun sessionKey(configured: String): ByteArray {
    if (configured.isNotBlank()) return Base64.getDecoder().decode(configured)
    LoggerFactory.getLogger("bank.identity").warn(
        "No bank.identity.sessionKey: sessions are sealed with a key made now, and open on this node only",
    )
    return ByteArray(32).also(SecureRandom()::nextBytes)
}
