package bank.approvals.app

import io.github.matthewjones372.lark.app.runApp
import java.nio.file.Path
import java.security.Security
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main() {
    // A peer's name resolves only once its pod runs, and the JDK remembers a failed lookup for 10 s: forget failures,
    // as the bank does, so a node that joined in that window can be probed back.
    Security.setProperty("networkaddress.cache.negative.ttl", "0")
    // The level Estate's debug switch writes to the bank-approvals-logging ConfigMap, mounted here (lark-bank spec 0026).
    System.getenv("LOG_LEVEL_FILE")?.let { LevelFile(Path.of(it)).follow(10.seconds) }
    exitProcess(runApp(BankApprovals).code)
}
