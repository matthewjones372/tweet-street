package bank.app

import io.github.matthewjones372.lark.app.runApp
import java.nio.file.Path
import java.security.Security
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main() {
    // A peer's name resolves only once its container or pod runs, and the JDK remembers a failed lookup for 10 s: a
    // node that joined in that window could not be probed back, and was downed as unreachable. Forget failures.
    // Set before the first lookup, which is when the JDK reads it.
    Security.setProperty("networkaddress.cache.negative.ttl", "0")
    // The level Estate's debug switch writes to the lark-bank-logging ConfigMap, mounted here (bank spec 0026).
    System.getenv("LOG_LEVEL_FILE")?.let { LevelFile(Path.of(it)).follow(10.seconds) }
    exitProcess(runApp(LarkBank).code)
}
