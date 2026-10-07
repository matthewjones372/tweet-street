package bank.access.fga

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * OpenFGA for the test JVM, started on first use as the cluster runs it but in memory: preshared keys, and the model in
 * this repository applied to a fresh store per test, so tests never see each other's relationships.
 */
object TestOpenFga {
    const val KEY = "test-key-0123456789"

    private val server: GenericContainer<*> by lazy {
        GenericContainer("openfga/openfga:v1.21.0")
            .withCommand("run")
            .withEnv(mapOf("OPENFGA_AUTHN_METHOD" to "preshared", "OPENFGA_AUTHN_PRESHARED_KEYS" to KEY, "OPENFGA_PLAYGROUND_ENABLED" to "false"))
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080))
            .apply { start() }
    }

    val url: String get() = "http://${server.host}:${server.getMappedPort(8080)}"

    /** A store of its own, named uniquely, with the model applied: what the access-model Job does to `bank`. */
    fun fresh(): Fga {
        val name = "test-${UUID.randomUUID()}"
        val model = Files.readString(Path.of("../model/model.json"))
        Fga.create(url, KEY, name, model)
        return Fga(url, KEY, name)
    }
}
