package bank.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Files

/** Bank spec 0026's `logging-configmaps`: the bank's level, from the file its ConfigMap is mounted as. */
class LevelFileSpec {
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val lark = LoggerFactory.getLogger("lark") as Logger
    private val kafka = LoggerFactory.getLogger("org.apache.kafka") as Logger
    private val before = listOf(root, lark, kafka).associateWith { it.level }
    private val file = Files.createTempFile("level", "")

    @AfterEach
    fun putBack() = before.forEach { (logger, level) -> logger.level = level }

    @Test
    fun `DEBUG in the file turns the bank's lines to DEBUG, and INFO turns them back`() {
        kafka.level = Level.WARN
        Files.writeString(file, "DEBUG\n")
        LevelFile(file).read() shouldBe Level.DEBUG
        LoggerFactory.getLogger("bank.app.Transfers").isDebugEnabled shouldBe true
        lark.level shouldBe Level.DEBUG
        kafka.level shouldBe Level.WARN

        Files.writeString(file, "info")
        LevelFile(file).read() shouldBe Level.INFO
        LoggerFactory.getLogger("bank.app.Transfers").isDebugEnabled shouldBe false
        lark.level shouldBe Level.INFO
    }

    @Test
    fun `a file that is missing, or names no level, changes nothing`() {
        root.level = Level.INFO
        LevelFile(file.resolveSibling("nowhere-${System.nanoTime()}")).read() shouldBe null
        Files.writeString(file, "LOUDER")
        LevelFile(file).read() shouldBe null
        root.level shouldBe Level.INFO
    }
}
