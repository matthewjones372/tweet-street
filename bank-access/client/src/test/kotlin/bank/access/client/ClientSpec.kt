package bank.access.client

import bank.access.fga.Fga
import bank.access.fga.TestOpenFga
import bank.access.fga.Tuple
import bank.events.v1.AccessDecision
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.KafkaContainer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Kafka and Apicurio for the test JVM, as the estate's topics are read: by Apicurio's own deserializer. */
object TestKafka {
    private val kafka by lazy { KafkaContainer("apache/kafka:3.9.1").apply { start() } }
    private val apicurio by lazy {
        GenericContainer("quay.io/apicurio/apicurio-registry:3.3.3").withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/apis/registry/v3/system/info").forStatusCode(200)).apply { start() }
    }
    val bootstrap: String get() = kafka.bootstrapServers
    val registry: String get() = "http://${apicurio.host}:${apicurio.getMappedPort(8080)}/apis/registry/v3"

    /** Every decision on the topic so far keyed [key], once [until] is satisfied or a minute has gone. */
    fun decisions(key: String, until: (List<AccessDecision>) -> Boolean): List<AccessDecision> {
        val properties = mapOf<String, Any>(
            "bootstrap.servers" to bootstrap, "group.id" to "test-${UUID.randomUUID()}", "auto.offset.reset" to "earliest",
            "apicurio.registry.url" to registry, "apicurio.registry.serde.read-indexes" to true,
            "apicurio.registry.serde.read-type-ref" to false,
            "apicurio.registry.deserializer.value.return-class" to AccessDecision::class.java.name,
        )
        val values = ProtobufKafkaDeserializer<AccessDecision>().apply { configure(properties, false) }
        return KafkaConsumer(properties, StringDeserializer(), values).use { consumer ->
            consumer.subscribe(listOf(DECISIONS))
            val seen = mutableListOf<AccessDecision>()
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (!until(seen) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).filter { it.key() == key }.forEach { seen += it.value() }
            }
            seen
        }
    }
}

/** Bank spec 0022's `access-client`: what every service asks bank-access through. */
class ClientSpec {
    private val run = UUID.randomUUID().toString().take(8)
    private val acc = "acc-$run"

    private fun store(): Fga = TestOpenFga.fresh().also { fga ->
        fga.write(
            listOf(
                Tuple("group:auditor#member", "auditor", "bank:lark"),
                Tuple("bank:lark", "bank", "account:$acc"),
                Tuple("person:ada", "owner", "account:$acc"),
                Tuple("person:aud", "member", "group:auditor"),
                Tuple("person:bob", "impersonator", "person:ada", Instant.now().plusSeconds(600)),
            ),
        )
    }

    @Test
    fun `every relation the client names is one the model has, so a renamed relation fails the build`() {
        val model = ObjectMapper().readTree(Path.of("../model/model.json").toFile())
        val inModel = model.path("type_definitions").flatMap { type ->
            type.path("relations").fieldNames().asSequence().map { "${type.path("type").asText()}#$it" }.toList()
        }.toSet()
        Relations.all.map { "${it.type}#${it.name}" }.filterNot(inModel::contains) shouldBe emptyList()
    }

    @Test
    fun `an owner may, a stranger may not, one acting may only with a grant, and a request asks each question once`() {
        val fga = store()
        val registry = SimpleMeterRegistry()
        val access = Access(fga, Decisions.none, registry)
        val ada = Asker("ada")
        access.check(ada, Account.viewer, acc) shouldBe Answer.Yes
        access.check(ada, Account.payer, acc) shouldBe Answer.Yes
        access.check(Asker("eve"), Account.viewer, acc) shouldBe Answer.No
        // Bob acting as Ada: his grant to act, then her own relation. Carl has no grant.
        access.check(Asker("ada", actor = "bob"), Account.viewer, acc) shouldBe Answer.Yes
        access.check(Asker("ada", actor = "carl"), Account.viewer, acc) shouldBe Answer.No

        val memo = Memo()
        repeat(5) { access.check(ada, Account.viewer, acc, memo) shouldBe Answer.Yes }
        registry.counter("bank.access.checks", "relation", "account#viewer", "answer", "yes").count() shouldBe 3.0
        access.listObjects(ada, Account.viewer) shouldContainExactly setOf(acc)
    }

    @Test
    fun `with OpenFGA stopped, or slower than the budget, every check is unanswered, which is a 503, and counted`() {
        val stopped = GenericContainer("openfga/openfga:v1.21.0").withCommand("run").withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080)).apply { start() }
        val url = "http://${stopped.host}:${stopped.getMappedPort(8080)}"
        stopped.stop()
        val registry = SimpleMeterRegistry()
        val gone = Access(Fga(url, "any", "bank", Access.BUDGET), Decisions.none, registry)
        val answer = gone.check(Asker("ada"), Account.viewer, acc)
        answer.shouldBeInstanceOf<Answer.Unanswered>()
        answer.status shouldBe 503
        registry.counter("bank.access.unanswered").count() shouldBe 1.0

        // One that answers, but after the budget: refused within it, not after it.
        val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> Thread.sleep(2_000); exchange.sendResponseHeaders(200, -1); exchange.close() }
            executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
            start()
        }
        try {
            val late = Access(Fga("http://127.0.0.1:${slow.address.port}", "any", "bank", Access.BUDGET), Decisions.none, registry)
            val started = System.nanoTime()
            late.check(Asker("ada"), Account.viewer, acc).shouldBeInstanceOf<Answer.Unanswered>()
            Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeLessThan 500
        } finally {
            slow.stop(0)
        }
    }

    @Test
    fun `a staff check and a refusal land on bank access-decisions, and a customer seeing their own does not`() {
        val fga = store()
        KafkaDecisions(DecisionSettings(TestKafka.bootstrap, TestKafka.registry), "test").use { decisions ->
            val access = Access(fga, decisions, SimpleMeterRegistry())
            access.check(Asker("ada"), Account.viewer, acc) shouldBe Answer.Yes
            access.check(Asker("aud", groups = setOf("auditor"), address = "192.0.2.20"), Account.viewer, acc) shouldBe Answer.Yes
            access.check(Asker("eve"), Account.payer, acc) shouldBe Answer.No
            access.check(Asker("ada", actor = "bob"), Account.payer, acc) shouldBe Answer.Yes

            val seen = TestKafka.decisions("account:$acc") { it.size >= 3 }
            seen.map { Triple(it.subject, it.relation, it.answer) } shouldContainExactly listOf(
                Triple("aud", "viewer", AccessDecision.Answer.ANSWER_YES),
                Triple("eve", "payer", AccessDecision.Answer.ANSWER_NO),
                Triple("ada", "payer", AccessDecision.Answer.ANSWER_YES),
            )
            seen[0].groupsList shouldBe listOf("auditor")
            seen[0].address shouldBe "192.0.2.20"
            seen[0].service shouldBe "test"
            seen[2].actor shouldBe "bob"
            seen.all { it.`object` == "account:$acc" && it.id.isNotEmpty() } shouldBe true
        }
    }
}
