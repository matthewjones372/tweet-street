package bank.approvals.app

import bank.approvals.api.Signing
import bank.approvals.api.Rules
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Credentials
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.PageGuard
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.oidc.oidc
import io.github.matthewjones372.pelican.oidc.signIn
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

/**
 * The identity provider as pelican-oidc sees it (lark-bank spec 0021), as the bank's: API tokens verified against
 * [IdentitySettings.issuer], and the pages signed in to as its client, the session sealed with the key every node shares.
 */
fun signing(settings: IdentitySettings): Signing {
    val signIn = oidc(settings.issuer, settings.audience).signIn(
        clientId = settings.clientId,
        callbackUrl = settings.callbackUrl,
        sessionKey = sessionKey(settings.sessionKey),
        clientSecret = settings.clientSecret,
    )
    val services = settings.services.associateWith { client -> oidc(settings.issuer, client) }
    return object : Signing {
        override val endpoints: List<ServerEndpoint> = signIn.endpoints
        override val authenticator: Authenticator = ServiceClients(signIn.authenticator, services)
        override val guard: PageGuard = signIn.guard
    }
}

/**
 * People's tokens and sessions as [people] verifies them; and a token issued to one of [services], an owning service's
 * client, as that service in `services`. Pocket ID's client-credentials tokens name the client as their audience and
 * carry no groups, so the client is what says it is an owning service.
 */
class ServiceClients(private val people: Authenticator, private val services: Map<String, Authenticator>) : Authenticator {
    override fun authenticate(credentials: Credentials): Identity? = try {
        people.authenticate(credentials)
    } catch (refused: Unauthenticated) {
        services.firstNotNullOfOrNull { (client, verifier) ->
            runCatching { verifier.authenticate(credentials) }.getOrNull()?.let { Identity(client, client, setOf(Rules.SERVICES), claims = it.claims) }
        } ?: throw refused
    }
}

private fun sessionKey(configured: String): ByteArray {
    if (configured.isNotBlank()) return Base64.getDecoder().decode(configured)
    LoggerFactory.getLogger("bank.approvals.identity").warn(
        "No approvals.identity.sessionKey: sessions are sealed with a key made now, and open on this node only",
    )
    return ByteArray(32).also(SecureRandom()::nextBytes)
}
