package bank.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The bank's log level, read from [file] (bank spec 0026): `INFO` or `DEBUG`, as Estate's debug switch writes it to the
 * `lark-bank-logging` ConfigMap mounted there. The root and Lark's loggers take it, and so the bank's own; the
 * libraries quietened in `logback.xml` keep their levels. A file that is missing, or says something that is not a
 * level, changes nothing.
 */
class LevelFile(private val file: Path) {
    private val taking = listOf(Logger.ROOT_LOGGER_NAME, "lark").map { LoggerFactory.getLogger(it) as Logger }

    /** Reads the file once, and sets the level it names; the level now, or null if the file said none. */
    fun read(): Level? {
        val said = runCatching { Files.readString(file).trim().uppercase() }.getOrNull() ?: return null
        val level = Level.toLevel(said, null) ?: return null
        taking.filter { it.level != level }.forEach { it.level = level }
        return level
    }

    /** Reads the file [every] while the bank runs, on a thread of its own that never holds the bank up. */
    fun follow(every: Duration): Thread =
        Thread.ofVirtual().name("log-level").start {
            while (true) {
                read()
                Thread.sleep(every.inWholeMilliseconds)
            }
        }
}
