package bank.app

import bank.api.OpenAccount
import bank.api.getAccount
import bank.api.openAccount
import bank.api.statement
import bank.events.v1.ApprovalEvent
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.signedInAs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException

/** Approvals as the bank calls it: each `applied` and `apply-failed` kept, with the token it came with. */
class StubApprovals : AutoCloseable {
    data class Call(val path: String, val authorization: String?, val body: String)

    val calls = CopyOnWriteArrayList<Call>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/requests") { exchange ->
            calls += Call(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readAllBytes().decodeToString())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    fun to(request: String): List<Call> = calls.filter { it.path.startsWith("/requests/$request/") }

    override fun close() = server.stop(0)
}

/** Approval events on `bank.approval-events`, framed as bank-approvals writes them. */
object ApprovalEvents {
    private val producer by lazy {
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to TestKafka.bootstrap)).use { admin ->
            try {
                admin.createTopics(listOf(NewTopic(ApprovalTopic.NAME, 12, 1))).all().get()
            } catch (failed: ExecutionException) {
                if (failed.cause !is TopicExistsException) throw failed
            }
        }
        KafkaProducer(mapOf<String, Any>("bootstrap.servers" to TestKafka.bootstrap), StringSerializer(), ByteArraySerializer())
    }

    fun send(vararg events: ApprovalEvent) = events.forEach { event ->
        producer.send(ProducerRecord(ApprovalTopic.NAME, event.requestId, KafkaPublisher.framed(1, event))).get()
    }

    private fun event(request: String, sequence: Long, subject: String, at: Instant) = ApprovalEvent.newBuilder()
        .setRequestId(request).setSequence(sequence).setAtMillis(at.toEpochMilli()).setKind(GRANT_KIND).setSubject(subject)

    /** [person], in [groups], asks to see [account] for [lasting]: what a person in support sends Approvals. */
    fun asked(
        request: String,
        person: String,
        account: String,
        lasting: String,
        groups: List<String> = listOf("support"),
        forWhom: String = person,
        access: Map<String, String> = mapOf("access" to "view", "account" to account),
    ) =
        event(request, 1, "bank/account/$account/viewer/$forWhom", Instant.now()).setRequested(
            ApprovalEvent.Requested.newBuilder()
                .setTitle("$forWhom to see $account").setBefore("").setAfter("$forWhom may see $account for $lasting")
                .putAllFacts(access + mapOf("person" to forWhom, "for" to lasting, "ticket" to "1234"))
                .setRequester(ApprovalEvent.Person.newBuilder().setSubject(person).setName(person).addAllGroups(groups))
                .setContentHash("hash-$request"),
        ).build()

    fun given(request: String, account: String, forWhom: String, at: Instant = Instant.now()) =
        event(request, 3, "bank/account/$account/viewer/$forWhom", at).setApprovalGiven(
            ApprovalEvent.ApprovalGiven.newBuilder().addApprovers("gil").setContentHash("hash-$request"),
        ).build()
}

/** Bank spec 0021's `staff`: support sees one account, and only while a grant approved in Approvals lives. */
class GrantsSpec {
    private val run = UUID.randomUUID().toString().take(8)

    private fun config(approvals: StubApprovals) = """
        bank.kafka.bootstrap = "${TestKafka.bootstrap}"
        bank.access.enabled = true
        bank.access.approvalsUrl = "${approvals.url}"
        bank.access.tokenUrl = "${TestIdentity.issuer.url}/token"
        bank.access.tokenForm = "grant_type=urn:lark-bank:test-token&subject=bank&groups=services&audience=bank-approvals"
    """.trimIndent()

    private fun ApiClient.status(account: String) = response(getAccount, account).status

    private fun eventually(seconds: Long = 30, what: String, done: () -> Boolean) {
        val until = System.nanoTime() + seconds * 1_000_000_000
        while (!done()) {
            check(System.nanoTime() < until) { "never: $what" }
            Thread.sleep(100)
        }
    }

    @Test
    fun `support sees the account a grant names while it lives, nothing else, and nothing a second after it ends`() {
        val account = "acc-$run"
        val other = "other-$run"
        StubApprovals().use { approvals ->
            TestCluster(1, extra = config(approvals)).running { (node) ->
                apiClient(node.server.baseUrl, JacksonCodecs).use { client ->
                    val ada = client.calling("ada")
                    ada.outcome(openAccount, In2(account, OpenAccount("GBP", "10.00"))).shouldBeOk()
                    ada.outcome(openAccount, In2(other, OpenAccount("GBP"))).shouldBeOk()
                    val sam = client.calling("sam", "support")
                    val gil = client.calling("gil", "support")
                    sam.status(account) shouldBe 404

                    // Asked first, so the grant's few seconds are not spent on the consumer joining its group.
                    val request = "req-$run"
                    ApprovalEvents.send(ApprovalEvents.asked(request, "sam", account, "PT4S"))
                    eventually(what = "the bank heard $request asked") { node.grants.asked(request) }
                    val approved = Instant.now()
                    ApprovalEvents.send(ApprovalEvents.given(request, account, "sam", approved))

                    eventually(what = "sam sees $account") { sam.status(account) == 200 }
                    sam.response(statement, In3(account, 50, null)).status shouldBe 200
                    sam.status(other) shouldBe 404
                    gil.status(account) shouldBe 404
                    // Acting as Ada is its own grant (`act-as`): a grant to view is not one.
                    client.signedInAsActor("ada", "sam").status(account) shouldBe 404

                    eventually(what = "Approvals told $request applied") { approvals.to(request).isNotEmpty() }
                    approvals.to(request).single().let { call ->
                        call.path shouldBe "/requests/$request/applied"
                        call.body shouldContain "hash-$request"
                        call.authorization.orEmpty() shouldStartWith "Bearer "
                    }

                    // A second after it ends.
                    Thread.sleep(maxOf(0, approved.plusSeconds(5).toEpochMilli() - System.currentTimeMillis()))
                    sam.status(account) shouldBe 404
                    sam.response(statement, In3(account, 50, null)).status shouldBe 404
                }
            }
        }
    }

    @Test
    fun `a grant to act as a customer, approved, lets that person act as them until it ends`() {
        StubApprovals().use { approvals ->
            TestCluster(1, extra = config(approvals)).running { (node) ->
                val request = "$run-act-as"
                val asked = ApprovalEvents.asked(request, "sam", "ada", "PT10M", access = mapOf("access" to "act-as", "customer" to "ada"))
                val approved = Instant.now()
                ApprovalEvents.send(asked, ApprovalEvents.given(request, "ada", "sam", approved))
                eventually(what = "Approvals told $request applied") { approvals.to(request).isNotEmpty() }
                approvals.to(request).single().path shouldBe "/requests/$request/applied"
                node.grants.actingUntil("sam", "ada")?.toEpochMilli() shouldBe approved.plusSeconds(600).toEpochMilli()
                node.grants.actingUntil("gil", "ada") shouldBe null
                node.grants.supports("sam", "ada") shouldBe false
            }
        }
    }

    @Test
    fun `a grant asked for someone else, for too long, or by someone outside support is never given, and Approvals hears why`() {
        val account = "acc-$run"
        StubApprovals().use { approvals ->
            TestCluster(1, extra = config(approvals)).running { (node) ->
                apiClient(node.server.baseUrl, JacksonCodecs).use { client ->
                    client.calling("ada").outcome(openAccount, In2(account, OpenAccount("GBP"))).shouldBeOk()
                    val asks = listOf(
                        ApprovalEvents.asked("$run-for-gil", "sam", account, "PT30M", forWhom = "gil") to "gil",
                        ApprovalEvents.asked("$run-too-long", "sam", account, "PT5H") to "sam",
                        ApprovalEvents.asked("$run-not-support", "eve", account, "PT30M", groups = emptyList()) to "eve",
                    )
                    asks.forEach { (asked, forWhom) ->
                        ApprovalEvents.send(asked, ApprovalEvents.given(asked.requestId, account, forWhom))
                    }
                    asks.forEach { (asked, _) ->
                        eventually(what = "Approvals told ${asked.requestId} failed") { approvals.to(asked.requestId).isNotEmpty() }
                        approvals.to(asked.requestId).single().path shouldBe "/requests/${asked.requestId}/apply-failed"
                    }
                    approvals.to("$run-too-long").single().body shouldContain "at most"
                    listOf("gil", "sam", "eve").forEach { who -> client.calling(who, "support").status(account) shouldBe 404 }
                    node.grants.held(account).shouldBeEmpty()
                }
            }
        }
    }
}

/** A client acting as [subject], really [actor]: the token an act-as session carries. */
private fun ApiClient.signedInAsActor(subject: String, actor: String): ApiClient =
    signedInAs(TestIdentity.token(subject, "support", actor = actor))
