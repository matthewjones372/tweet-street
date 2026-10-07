package bank.app

import bank.domain.Bank
import bank.issuer.TestIssuer
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.signedInAs
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.typesafe.overridingConfig
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.pelican.pekko.PelicanServer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** One Postgres for the test JVM, in a container started on first use, and an empty database in it per cluster. */
object TestPostgres {
    private val server: PostgreSQLContainer by lazy {
        PostgreSQLContainer(
            DockerImageName.parse("public.ecr.aws/docker/library/postgres:17").asCompatibleSubstituteFor("postgres"),
        ).apply {
            withUsername("postgres")
            withPassword("postgres")
            // Every node's pools, across the clusters a test JVM runs at once, share this one server.
            // A server thrown away after the run: no password, so no connection pays for SCRAM's key stretching.
            withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
            withCommand("postgres", "-c", "max_connections=1000")
            start()
        }
    }
    private val made = AtomicInteger()

    /** An empty database, as a JDBC URL that carries its user and password. */
    fun fresh(): String {
        val name = "bank_${made.incrementAndGet()}"
        DriverManager.getConnection(url(server.databaseName)).use { admin ->
            admin.createStatement().use { it.execute("create database $name") }
        }
        return url(name)
    }

    private fun url(database: String) =
        "jdbc:postgresql://${server.host}:${server.getMappedPort(5432)}/$database?user=postgres&password=postgres"
}

/**
 * The identity provider every node in a test trusts (bank spec 0021), and the tokens tests call with. A token is a
 * real one, verified by the node against this issuer's keys over HTTP, as production verifies Pocket ID's.
 */
object TestIdentity {
    val issuer: TestIssuer by lazy { TestIssuer() }

    /** A customer's token, or staff's with [groups]. */
    fun token(subject: String, vararg groups: String, actor: String? = null): String =
        issuer.token(subject, groups.toList(), actor = actor)

    /** The same 32 bytes on every node, so a session sealed on one opens on the others. */
    const val SESSION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
}

/** A client calling as [subject]: a customer, or staff with [groups]. */
fun ApiClient.calling(subject: String, vararg groups: String): ApiClient = signedInAs(TestIdentity.token(subject, *groups))

/** What a test holds of one running node. */
class TestNode(
    val server: PelicanServer,
    val bank: Bank,
    val reads: JdbcReadModels,
    val cluster: Cluster,
    val persistence: Persistence,
    val grants: JdbcGrants,
)

/**
 * [count] ports, each different: every socket held open until all are chosen. Chosen one after another, the kernel can hand the
 * same port back once its socket closes, and a node whose web port was another's cluster port could not bind it.
 */
private fun freePorts(count: Int): List<Int> {
    val held = List(count) { ServerSocket(0) }
    return try {
        held.map { it.localPort }
    } finally {
        held.forEach { it.close() }
    }
}

/**
 * Nodes of one cluster, all on [database], each on its own ports. With [journals] above 1, the journal is split across
 * that many databases (lark spec 0088), [database] the first of them.
 */
class TestCluster(
    size: Int,
    journals: Int = 1,
    private val database: String = TestPostgres.fresh(),
    /** Config every node takes, after the rest. */
    private val extra: String = "",
) {
    private val others = (2..journals).joinToString(",") { "db-$it=${TestPostgres.fresh()}" }
    // Ascending, so node 0 holds the lowest seed: with static seeds only the lowest forms the cluster.
    private val ports = freePorts(size * 2)
    private val remoting = ports.take(size).sorted()
    // Known before a node starts, so its sign-in knows where the provider sends people back to.
    private val web = ports.drop(size)
    private val seeds = remoting.joinToString(", ") { "\"127.0.0.1:$it\"" }

    fun node(index: Int, extra: String = ""): Module =
        (bankModule + single { server: PelicanServer, bank: Bank, reads: JdbcReadModels, cluster: Cluster, persistence: Persistence,
            grants: JdbcGrants ->
            TestNode(server, bank, reads, cluster, persistence, grants)
        }).overridingConfig(
            """
            bank.port = ${web[index]}
            bank.identity.issuer = "${TestIdentity.issuer.url}"
            bank.identity.callbackUrl = "http://127.0.0.1:${web[index]}/callback"
            bank.identity.sessionKey = "${TestIdentity.SESSION_KEY}"
            bank.cluster.node.name = "n$index"
            bank.cluster.node.port = ${remoting[index]}
            bank.cluster.static.seeds = [$seeds]
            bank.cluster.gossip.formAfter = 1s
            bank.cluster.downing.stableAfter = 3s
            # A node downed in a test stays up for the test to look at, rather than ending the test JVM.
            bank.cluster.whenDowned = stay
            bank.entities.legTimeout = 1s
            bank.readModels.within = 50ms
            bank.readModels.sweepEvery = 500ms
            bank.readModels.stuckAfter = 2s
            bank.database.url = "$database"
            bank.database.user = "postgres"
            bank.database.password = "postgres"
            bank.database.poolSize = 16
            bank.journal.databases = "$others"
            $extra
            """.trimIndent(),
        )

    /** Every node started, one after another so each joins the one before, and all given back after [block]. */
    fun <A> running(block: (List<TestNode>) -> A): A = start(0, emptyList(), block)

    private fun <A> start(index: Int, started: List<TestNode>, block: (List<TestNode>) -> A): A =
        if (index == remoting.size) {
            block(started)
        } else {
            node(index, extra).use { node: TestNode -> start(index + 1, started + node, block) }
                .fold({ error -> error("node $index did not start: $error") }, { it })
        }
}

/** A node that runs on a thread of its own until [stop] is counted down, so a test can take it away mid-run. */
class Detached(module: Module) {
    private val stop = CountDownLatch(1)
    private val up = CountDownLatch(1)
    @Volatile var node: TestNode? = null
    private val thread = Thread.ofPlatform().start {
        try {
            module.use { started: TestNode ->
                node = started
                up.countDown()
                stop.await()
            }.onLeft { error -> System.err.println("detached node did not start: $error") }
        } finally {
            // Whether it started, failed, or threw: awaitUp answers rather than waiting for ever.
            up.countDown()
        }
    }

    fun awaitUp(): TestNode {
        up.await()
        return checkNotNull(node) { "the node did not start" }
    }

    /** Releases the node's graph, which leaves the cluster, and waits until it has. */
    fun stop() {
        stop.countDown()
        thread.join()
    }
}

/** Polls [done] until it holds or [seconds] pass, and answers whether it held. */
fun eventually(seconds: Long = 30, done: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (System.nanoTime() < deadline) {
        if (done()) return true
        Thread.sleep(100)
    }
    return done()
}
