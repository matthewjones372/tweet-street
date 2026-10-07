package bank.approvals.app

import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.events.v1.ApprovalEvent
import bank.issuer.TestIssuer
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.signedInAs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** One Postgres for the test JVM, and an empty database in it per cluster, as lark-bank's tests have. */
object TestPostgres {
    private val server: PostgreSQLContainer by lazy {
        PostgreSQLContainer(
            DockerImageName.parse("public.ecr.aws/docker/library/postgres:17").asCompatibleSubstituteFor("postgres"),
        ).apply {
            withUsername("postgres")
            withPassword("postgres")
            withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
            start()
        }
    }
    private val made = AtomicInteger()

    fun fresh(): String {
        val name = "approvals_${made.incrementAndGet()}"
        DriverManager.getConnection(url(server.databaseName)).use { admin ->
            admin.createStatement().use { it.execute("create database $name") }
        }
        return url(name)
    }

    private fun url(database: String) =
        "jdbc:postgresql://${server.host}:${server.getMappedPort(5432)}/$database?user=postgres&password=postgres"
}

/** The bank's test identity provider (lark-bank spec 0021): tokens verified by each node over HTTP, as Pocket ID's are. */
object TestIdentity {
    val issuer: TestIssuer by lazy { TestIssuer() }

    fun token(subject: String, vararg groups: String): String =
        issuer.token(subject, groups.toList(), audience = "bank-approvals")

    /** The key every test node seals sessions with, so a session made on one opens on another. */
    const val SESSION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
}

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

    /**
     * Every record on the topic so far whose key is one of [keys], read as lark-bank-events' README tells a consumer
     * to, by Apicurio's own deserializer, in order, until [until] holds of them or [seconds] pass.
     */
    fun read(keys: Set<String>, seconds: Long = 60, until: (List<ApprovalEvent>) -> Boolean): List<ApprovalEvent> =
        records(keys, seconds) { seen -> until(seen.map { it.value() }) }.map { it.value() }

    /** As [read], each record whole: its headers are what a consumer continues a trace from (lark-bank spec 0024). */
    fun records(
        keys: Set<String>,
        seconds: Long = 60,
        until: (List<ConsumerRecord<String, ApprovalEvent>>) -> Boolean,
    ): List<ConsumerRecord<String, ApprovalEvent>> {
        val properties = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            "apicurio.registry.url" to registry,
            "apicurio.registry.serde.read-indexes" to true,
            "apicurio.registry.serde.read-type-ref" to false,
            "apicurio.registry.deserializer.value.return-class" to ApprovalEvent::class.java.name,
        )
        // Given as an instance, Kafka does not configure it: that is ours to do.
        val values = ProtobufKafkaDeserializer<ApprovalEvent>().apply { configure(properties, false) }
        return KafkaConsumer(properties, StringDeserializer(), values).use { consumer ->
            consumer.subscribe(listOf(ApprovalTopic.NAME))
            val seen = mutableListOf<ConsumerRecord<String, ApprovalEvent>>()
            val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
            while (!until(seen) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).filter { record -> record.key() in keys }.forEach { record -> seen += record }
            }
            seen
        }
    }
}

/** The owning service as a test sees it: every asking again, in order, and the history it came with. */
class RecordingOwners : Owners {
    val asked: MutableList<RequestId> = CopyOnWriteArrayList()
    val histories = ConcurrentHashMap<RequestId, List<RequestEvent>>()

    override fun askAgain(id: RequestId, history: List<RequestEvent>) {
        asked += id
        histories[id] = history
    }

    fun times(id: RequestId): Int = asked.count { it == id }
}

class TestNode(val server: PelicanServer, val entities: Entities, val cluster: Cluster, val audit: JournalAudit) {
    fun client(): ApiClient = apiClient(server.baseUrl, JacksonCodecs)

    fun calling(subject: String, vararg groups: String): ApiClient = client().signedInAs(TestIdentity.token(subject, *groups))
}

/** Calls from [address], as the ingress forwards it, with [agent] as the user agent. */
fun ApiClient.from(address: String, agent: String): ApiClient {
    val underneath = transport
    val forwarded = object : Transport {
        override fun send(request: RequestSpec): ResponseSpec =
            underneath.send(request.withHeader("X-Forwarded-For", "$address, 198.51.100.1").withHeader("User-Agent", agent))
    }
    return ApiClient(forwarded, codecs, prefers)
}

/** Calls inside the trace [traceparent] names, as a caller in one sends it. */
fun ApiClient.inTrace(traceparent: String): ApiClient {
    val underneath = transport
    val traced = object : Transport {
        override fun send(request: RequestSpec): ResponseSpec = underneath.send(request.withHeader("traceparent", traceparent))
    }
    return ApiClient(traced, codecs, prefers)
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Policies for tests: two approvers from risk for most, a short expiry for one kind, and one approved by nobody. */
val testPolicies: Path by lazy {
    Files.createTempFile("policies", ".yaml").also { path ->
        Files.writeString(
            path,
            """
            - kind: test.change
              approvers: [ risk ]
              needed: 2
              expiresAfter: 1h
            - kind: test.short
              approvers: [ risk ]
              needed: 2
              expiresAfter: 2s
            - kind: test.auto
              approvers: [ risk ]
              needed: 2
              autoApprove:
                - when: { change: describe-only }
            """.trimIndent(),
        )
    }
}

/**
 * Nodes of one cluster on one database, each on its own ports, publishing to the test Kafka and sharing [owners];
 * with [onKafka], the owner is asked again on Kafka instead, as in production.
 */
class TestCluster(
    size: Int,
    val owners: RecordingOwners = RecordingOwners(),
    private val onKafka: Boolean = false,
    val database: String = TestPostgres.fresh(),
    /** Config every node takes, after the rest. */
    private val extra: String = "",
) {
    private val remoting = List(size) { freePort() }.sorted()
    private val web = List(size) { freePort() }
    private val seeds = remoting.joinToString(", ") { "\"127.0.0.1:$it\"" }

    fun node(index: Int): Module =
        (approvalsModule(if (onKafka) kafkaOwners else single<Owners> { owners }) +
            single { server: PelicanServer, entities: Entities, cluster: Cluster, audit: JournalAudit -> TestNode(server, entities, cluster, audit) })
            .overridingConfig(
            """
            approvals.port = ${web[index]}
            approvals.cluster.node.name = "a$index"
            approvals.cluster.node.port = ${remoting[index]}
            approvals.cluster.static.seeds = [$seeds]
            approvals.cluster.gossip.formAfter = 1s
            approvals.cluster.downing.stableAfter = 3s
            approvals.cluster.whenDowned = stay
            approvals.entities.applyEvery = 300ms
            approvals.entities.askTimeout = 5s
            approvals.database.url = "$database"
            approvals.database.user = "postgres"
            approvals.database.password = "postgres"
            approvals.policies = "$testPolicies"
            approvals.identity.issuer = "${TestIdentity.issuer.url}"
            approvals.identity.services = "checks-service"
            approvals.identity.callbackUrl = "http://127.0.0.1:${web[index]}/callback"
            approvals.identity.sessionKey = "${TestIdentity.SESSION_KEY}"
            approvals.kafka.bootstrap = "${TestKafka.bootstrap}"
            approvals.kafka.registry = "${TestKafka.registry}"
            approvals.kafka.every = 100ms
            $extra
            """.trimIndent(),
        )
}

/** A node on a thread of its own until [stop], so a test can take it away mid-run. */
class Detached(module: Module) {
    private val stop = CountDownLatch(1)
    private val up = CountDownLatch(1)
    @Volatile var node: TestNode? = null
    private val thread = Thread.ofPlatform().start {
        module.use { started: TestNode ->
            node = started
            up.countDown()
            stop.await()
        }.onLeft { error -> System.err.println("detached node did not start: $error"); up.countDown() }
    }

    fun awaitUp(): TestNode {
        up.await()
        return checkNotNull(node) { "the node did not start" }
    }

    fun stop() {
        stop.countDown()
        thread.join()
    }
}

/** Polls [done] until it holds or [seconds] pass, and answers whether it held. */
fun eventually(seconds: Long = 30, done: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (System.nanoTime() < deadline) {
        if (runCatching(done).getOrDefault(false)) return true
        Thread.sleep(100)
    }
    return done()
}
