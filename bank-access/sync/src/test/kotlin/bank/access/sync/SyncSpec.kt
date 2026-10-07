package bank.access.sync

import bank.access.fga.Fga
import bank.access.fga.TestOpenFga
import bank.access.fga.Tuple
import bank.events.v1.AccountEvent
import bank.events.v1.ApprovalEvent
import bank.events.v1.Money
import bank.events.v1.Opened
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.testcontainers.kafka.KafkaContainer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Kafka for the test JVM, with the two topics access-sync reads made as the bank and Approvals make them. */
object TestKafka {
    private val kafka by lazy { KafkaContainer("apache/kafka:3.9.1").apply { start() } }
    val bootstrap: String get() = kafka.bootstrapServers
    private val made by lazy {
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrap)).use { admin ->
            admin.createTopics(listOf(NewTopic(ACCOUNTS, 12, 1), NewTopic(APPROVALS, 12, 1))).all().get()
        }
    }
    private val producer by lazy {
        made
        KafkaProducer(mapOf<String, Any>("bootstrap.servers" to bootstrap), StringSerializer(), ByteArraySerializer())
    }

    /** [message] keyed by [key], framed as Apicurio's serializer frames it: a zero, a content id, a zero index. */
    fun send(topic: String, key: String, message: com.google.protobuf.Message, headers: Map<String, String> = emptyMap()) {
        val out = ByteArrayOutputStream()
        out.write(0)
        out.write(ByteBuffer.allocate(4).putInt(1).array())
        out.write(0)
        message.writeTo(out)
        val record = ProducerRecord(topic, key, out.toByteArray())
        headers.forEach { (name, value) -> record.headers().add(name, value.toByteArray()) }
        producer.send(record).get()
    }

    fun ready() = made
}

/** Pocket ID's admin API as access-sync reads it: groups, a page at a time, and each group's members. */
class StubPocketId : AutoCloseable {
    val groups = ConcurrentHashMap<String, Set<String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/user-groups") { exchange ->
            val id = exchange.requestURI.path.removePrefix("/api/user-groups").trim('/')
            val body = if (id.isEmpty()) {
                val data = groups.keys.sorted().joinToString(",") { """{"id":"g-$it","name":"$it"}""" }
                """{"data":[$data],"pagination":{"totalPages":1,"currentPage":1}}"""
            } else {
                val name = id.removePrefix("g-")
                val users = groups[name].orEmpty().joinToString(",") { """{"id":"$it","username":"$it"}""" }
                """{"id":"$id","name":"$name","users":[$users]}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (exchange.requestHeaders.getFirst("X-API-KEY") == "pocket-key") 200 else 401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

/** Bank spec 0022's `access-sync`: every relationship derived from something already recorded, and rebuilt from it. */
class SyncSpec {
    private val run = UUID.randomUUID().toString().take(8)

    private fun eventually(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + 60_000_000_000
        while (!done()) {
            check(System.nanoTime() < until) { "never: $what" }
            Thread.sleep(200)
        }
    }

    private fun settings(fga: Fga, pocketId: StubPocketId, fromStart: Boolean = false) = SyncSettings(
        kafka = KafkaSettings(TestKafka.bootstrap, group = "access-sync-$run"),
        fgaUrl = TestOpenFga.url, fgaToken = TestOpenFga.KEY, store = fga.store,
        pocketIdUrl = pocketId.url, pocketIdKey = "pocket-key",
        groupsEvery = java.time.Duration.ofSeconds(1), fromStart = fromStart,
    )

    private fun opened(account: String, owner: String) = AccountEvent.newBuilder().setAccountId(account).setSequence(1)
        .setAtMillis(System.currentTimeMillis())
        .setOpened(Opened.newBuilder().setOwner(owner).setInitial(Money.newBuilder().setCurrency("GBP").setAmount("0.00")).setReference("open:$account"))
        .build()

    private fun approval(request: String, sequence: Long, at: Instant, body: ApprovalEvent.Builder.() -> Unit) =
        ApprovalEvent.newBuilder().setRequestId(request).setSequence(sequence).setAtMillis(at.toEpochMilli())
            .setKind("bank.access-grant").setSubject("bank/grant/$request").apply(body).build()

    /** A grant asked, approved and applied, as bank-approvals publishes it. */
    private fun grant(request: String, person: String, facts: Map<String, String>, approved: Instant) {
        val hash = "hash-$request"
        listOf(
            approval(request, 1, approved) {
                setRequested(
                    ApprovalEvent.Requested.newBuilder().setTitle(request).putAllFacts(facts + ("person" to person))
                        .setRequester(ApprovalEvent.Person.newBuilder().setSubject(person).addGroups("support")).setContentHash(hash),
                )
            },
            approval(request, 2, approved) { setApprovalGiven(ApprovalEvent.ApprovalGiven.newBuilder().addApprovers("gil").setContentHash(hash)) },
            approval(request, 3, approved) { setApplied(ApprovalEvent.Applied.newBuilder().setContentHash(hash)) },
        ).forEach { TestKafka.send(APPROVALS, request, it) }
    }

    @Test
    fun `an opened account's owner sees it, an approved grant lets Bob see it until it expires, and a group is its members`() {
        TestKafka.ready()
        val fga = TestOpenFga.fresh()
        val acc = "acc-$run"
        StubPocketId().use { pocketId ->
            pocketId.groups["auditor"] = setOf("aud-$run")
            AccessSync(settings(fga, pocketId)).use { sync ->
                sync.start()
                TestKafka.send(ACCOUNTS, acc, opened(acc, "ada-$run"))
                eventually("Ada sees $acc") { fga.check("person:ada-$run", "viewer", "account:$acc") }
                fga.check("person:ada-$run", "payer", "account:$acc") shouldBe true
                fga.check("person:eve-$run", "viewer", "account:$acc") shouldBe false

                // Bob's grant, approved now for 4 seconds: he sees acc while it lives, and never pays.
                val approved = Instant.now()
                grant("view-$run", "bob-$run", mapOf("access" to "view", "account" to acc, "for" to "PT4S"), approved)
                eventually("Bob sees $acc") { fga.check("person:bob-$run", "viewer", "account:$acc") }
                fga.check("person:bob-$run", "payer", "account:$acc") shouldBe false
                // And a grant to act as Ada, which the bank asks before anything else when Bob acts as her.
                grant("act-$run", "bob-$run", mapOf("access" to "act-as", "customer" to "ada-$run", "for" to "PT10M"), approved)
                eventually("Bob may act as Ada") { fga.check("person:bob-$run", "impersonator", "person:ada-$run") }

                // Pocket ID's auditors see every account; one taken out of the group no longer does.
                eventually("the auditor sees $acc") { fga.check("person:aud-$run", "viewer", "account:$acc") }
                pocketId.groups["auditor"] = emptySet()
                eventually("the auditor, removed, does not") { !fga.check("person:aud-$run", "viewer", "account:$acc") }

                Thread.sleep(maxOf(0, approved.plusSeconds(5).toEpochMilli() - System.currentTimeMillis()))
                fga.check("person:bob-$run", "viewer", "account:$acc") shouldBe false
            }
        }
    }

    @Test
    fun `an emptied store, rebuilt from offset zero and Pocket ID, answers every check as it did before`() {
        TestKafka.ready()
        val fga = TestOpenFga.fresh()
        val accounts = (1..3).map { "rebuild-$it-$run" }
        StubPocketId().use { pocketId ->
            pocketId.groups["auditor"] = setOf("aud-$run")
            pocketId.groups["support"] = setOf("bob-$run")
            AccessSync(settings(fga, pocketId)).use { sync ->
                sync.start()
                accounts.forEachIndexed { i, acc -> TestKafka.send(ACCOUNTS, acc, opened(acc, "owner-$i-$run")) }
                grant("rebuild-$run", "bob-$run", mapOf("access" to "view", "account" to accounts[0], "for" to "PT1H"), Instant.now())
                eventually("every relationship written") {
                    fga.check("person:owner-2-$run", "viewer", "account:${accounts[2]}") &&
                        fga.check("person:bob-$run", "viewer", "account:${accounts[0]}") &&
                        fga.check("person:aud-$run", "viewer", "account:${accounts[1]}")
                }
            }
            val people = listOf("owner-0", "owner-1", "owner-2", "bob", "aud", "eve").map { "person:$it-$run" }
            val asked = people.flatMap { who -> accounts.flatMap { acc -> listOf("viewer", "payer").map { Triple(who, it, "account:$acc") } } }
            val before = asked.associateWith { (who, relation, obj) -> fga.check(who, relation, obj) }

            val all = fga.read()
            fga.delete(all)
            fga.read().size shouldBe 0
            asked.count { (who, relation, obj) -> fga.check(who, relation, obj) } shouldBe 0

            AccessSync(settings(fga, pocketId, fromStart = true)).use { sync ->
                sync.start()
                eventually("the store rebuilt") { fga.read().map(Tuple::key).toSet() == all.map(Tuple::key).toSet() }
            }
            asked.associateWith { (who, relation, obj) -> fga.check(who, relation, obj) } shouldBe before
        }
    }
}
