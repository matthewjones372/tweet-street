package bank.app

import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.events.v1.AccountEvent
import bank.events.v1.TransferEvent
import com.fasterxml.jackson.databind.ObjectMapper
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.UUID

/** Kafka and Apicurio for the test JVM, each started on first use, as Postgres is. */
object TestKafka {
    private val kafka: KafkaContainer by lazy { KafkaContainer("apache/kafka:3.9.1").apply { start() } }
    private val apicurio: GenericContainer<*> by lazy {
        GenericContainer("quay.io/apicurio/apicurio-registry:3.3.3")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/apis/registry/v3/system/info").forStatusCode(200))
            .apply { start() }
    }

    val bootstrap: String get() = kafka.bootstrapServers
    val registry: String get() = "http://${apicurio.host}:${apicurio.getMappedPort(8080)}/apis/registry/v3"

    /** What a node takes to publish every event here. */
    val config: String get() = """
        bank.kafka.enabled = true
        bank.kafka.bootstrap = "$bootstrap"
        bank.kafka.registry = "$registry"
    """.trimIndent()

    /** Every record on [topic] so far whose key is one of [keys], read by Apicurio's own deserializer, in order. */
    inline fun <reified M : com.google.protobuf.Message> read(topic: String, keys: Set<String>, noinline until: (List<M>) -> Boolean): List<M> =
        read(topic, M::class.java, keys, until)

    fun <M : com.google.protobuf.Message> read(topic: String, type: Class<M>, keys: Set<String>, until: (List<M>) -> Boolean): List<M> {
        val properties = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            // As lark-bank-events' README tells a consumer to read the bank's records.
            "apicurio.registry.url" to registry,
            "apicurio.registry.serde.read-indexes" to true,
            "apicurio.registry.serde.read-type-ref" to false,
            "apicurio.registry.deserializer.value.return-class" to type.name,
        )
        // Given as an instance, Kafka does not configure it: that is ours to do.
        val values = ProtobufKafkaDeserializer<M>().apply { configure(properties, false) }
        val consumer = KafkaConsumer(properties, StringDeserializer(), values)
        return consumer.use {
            it.subscribe(listOf(topic))
            val seen = mutableListOf<M>()
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (!until(seen) && System.nanoTime() < deadline) {
                it.poll(Duration.ofMillis(500)).filter { record -> record.key() in keys }.forEach { record -> seen += record.value() }
            }
            seen
        }
    }
}

/** Bank spec 0015: every event published, as a contract Apicurio holds. */
class PublishingSpec {
    private val run = UUID.randomUUID().toString().take(8)

    @Test
    fun `a transfer's events arrive on both topics, and Apicurio's own deserializer reads them`() {
        val (from, to, transfer) = Triple(AccountId("pub-a-$run"), AccountId("pub-b-$run"), TransferId("pub-t-$run"))
        TestCluster(1, extra = TestKafka.config).running { (node) ->
            node.bank.open(from, "Ada", gbp(10_000), "open:$from").shouldBeRight()
            node.bank.open(to, "Bob", gbp(0), "open:$to").shouldBeRight()
            node.bank.transfer(transfer, from, to, gbp(2_500)).shouldBeRight()
            eventually { node.bank.transferStatus(transfer).getOrNull()?.status == TransferStatus.Completed } shouldBe true

            val transfers = TestKafka.read<TransferEvent>(EventStream.TRANSFERS.topic, setOf(transfer.value)) { it.size >= 3 }
            transfers.map { it.eventCase } shouldBe listOf(
                TransferEvent.EventCase.REQUESTED, TransferEvent.EventCase.SOURCE_DEBITED, TransferEvent.EventCase.DESTINATION_CREDITED,
            )
            transfers.map { it.sequence } shouldBe listOf(1L, 2L, 3L)
            transfers.forEach { event ->
                event.fromAccount shouldBe from.value
                event.toAccount shouldBe to.value
            }
            transfers.first().requested.amount.amount shouldBe "25.00"

            val accounts = TestKafka.read<AccountEvent>(EventStream.ACCOUNTS.topic, setOf(from.value, to.value)) { seen ->
                seen.any { it.hasCredited() } && seen.any { it.hasDebited() }
            }
            accounts.filter { it.accountId == from.value }.map { it.eventCase } shouldContainAll
                listOf(AccountEvent.EventCase.OPENED, AccountEvent.EventCase.DEBITED)
            val credited = accounts.single { it.hasCredited() }
            credited.accountId shouldBe to.value
            credited.credited.transferId shouldBe transfer.value
            credited.credited.amount.amount shouldBe "25.00"
        }
    }

    @Test
    fun `a publisher stopped mid-run and started again sends what it had not, and skips nothing`() {
        val account = AccountId("pub-r-$run")
        val database = TestPostgres.fresh()
        TestCluster(1, database = database, extra = TestKafka.config).running { (node) ->
            node.bank.open(account, "Cy", gbp(0), "open:$account").shouldBeRight()
            // Stopped straight after, while the publisher is still sending some of these.
            (1..40).forEach { node.bank.deposit(account, gbp(it.toLong()), "d-$it").shouldBeRight() }
        }
        TestCluster(1, database = database, extra = TestKafka.config).running { (node) ->
            (41..60).forEach { node.bank.deposit(account, gbp(it.toLong()), "d-$it").shouldBeRight() }
            val published = TestKafka.read<AccountEvent>(EventStream.ACCOUNTS.topic, setOf(account.value)) { seen ->
                seen.map { it.sequence }.toSet().size >= 61
            }
            // At least once: resent events are the same (account, sequence), and none is missing.
            published.map { it.sequence }.toSet() shouldBe (1L..61L).toSet()
            published.groupBy { it.sequence }.values.forEach { copies -> copies.distinct().size shouldBe 1 }
        }
    }

    @Test
    fun `a schema that breaks the published one is refused, and nothing publishes with it`() {
        val registry = Apicurio(TestKafka.registry)
        registry.registerAll()
        val money = registry.register(
            EventStream.MONEY.replace('/', '.'),
            resource(EventStream.MONEY),
            references = emptyList(),
        )
        val reference = ObjectMapper().createObjectNode().put("groupId", "default").put("artifactId", money.artifact)
            .put("version", money.version).put("name", EventStream.MONEY)
        // Retyped: a sequence as a string, which no earlier reader can read.
        val broken = resource(EventStream.ACCOUNTS.schema).replace("int64 sequence = 2;", "string sequence = 2;")
        val refused = shouldThrow<SchemaRefused> { registry.register(EventStream.ACCOUNTS.artifact, broken, listOf(reference)) }
        refused.message shouldContain EventStream.ACCOUNTS.artifact
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)).use { String(it.readAllBytes()) }
}
