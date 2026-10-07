package bank.approvals.app

import bank.approvals.domain.Condition
import bank.approvals.domain.Policies
import bank.approvals.domain.Policy
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * `policies.yaml`, read at start and again whenever it changes, so a change Flux ships takes effect on the next
 * request without a restart. Its version is its content's hash: the same file on every node is the same version, and
 * a request records the exact file it was decided under.
 */
class PolicyFile(private val path: Path) {
    private class Read(val modified: Long, val policies: Policies)

    private val read = AtomicReference(load())

    fun current(): Policies {
        val modified = Files.getLastModifiedTime(path).toMillis()
        val held = read.get()
        if (modified == held.modified) return held.policies
        return load().also(read::set).policies
    }

    private fun load(): Read {
        val bytes = Files.readAllBytes(path)
        return Read(Files.getLastModifiedTime(path).toMillis(), parsePolicies(String(bytes), version(bytes)))
    }
}

private fun version(bytes: ByteArray): String =
    "policies@" + MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }

/** A list of policies, one per kind; a kind listed twice is refused, since which one wins would be an accident. */
fun parsePolicies(yaml: String, version: String): Policies {
    val entries = ObjectMapper(YAMLFactory()).readValue(yaml, List::class.java).orEmpty().map { it as Map<*, *> }
    val policies = entries.map { entry ->
        val kind = entry["kind"] as? String ?: error("a policy with no kind: $entry")
        Policy(
            kind = kind,
            approvers = (entry["approvers"] as? List<*>).orEmpty().map { it.toString() }.toSet(),
            needed = (entry["needed"] as? Int) ?: error("$kind says nothing of how many must approve"),
            expiresAfter = (entry["expiresAfter"] as? String)?.let(::duration) ?: Duration.ofDays(7),
            autoApprove = (entry["autoApprove"] as? List<*>).orEmpty().map { rule ->
                val facts = (rule as Map<*, *>)["when"] as? Map<*, *> ?: error("$kind has an autoApprove with no when")
                Condition(facts.entries.associate { (key, value) -> key.toString() to value.toString() })
            },
        )
    }
    val twice = policies.groupBy { it.kind }.filterValues { it.size > 1 }.keys
    require(twice.isEmpty()) { "policies.yaml lists $twice more than once" }
    return Policies(version, policies.associateBy { it.kind })
}

/** `7d`, `12h`, `30m` or `45s`. */
private fun duration(text: String): Duration {
    val amount = text.dropLast(1).toLongOrNull() ?: error("not a duration: $text")
    return when (text.last()) {
        'd' -> Duration.ofDays(amount)
        'h' -> Duration.ofHours(amount)
        'm' -> Duration.ofMinutes(amount)
        's' -> Duration.ofSeconds(amount)
        else -> error("not a duration: $text; say 7d, 12h, 30m or 45s")
    }
}
