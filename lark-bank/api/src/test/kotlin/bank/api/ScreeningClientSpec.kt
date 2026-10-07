package bank.api

import io.github.matthewjones372.pelican.codegen.kotlinClient
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Spec 0018: the client the bank calls Screening through is the one its description generates. */
class ScreeningClientSpec {
    private val committed = Path.of("src/main/kotlin/bank/api/screening/ScreeningClient.kt")

    @Test
    fun `the committed client is the one the description generates`() {
        val generated = screeningSpec().kotlinClient(packageName = "bank.api.screening", clientName = "ScreeningClient")
        // -Pregenerate (see the build file) writes it instead, for a change to the description.
        if (System.getProperty("bank.regenerate") == "true") {
            Files.createDirectories(committed.parent)
            Files.writeString(committed, generated)
        }
        Files.readString(committed) shouldBe generated
    }
}
