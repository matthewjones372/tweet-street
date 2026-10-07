package bank.access.client

import bank.events.v1.AccessDecision
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ExecutionException

/** The topic decisions are kept on, as lark-bank-events describes it. */
const val DECISIONS = "bank.access-decisions"

/** Where decisions that someone may want to account for go (lark-bank spec 0022, settled 7). */
fun interface Decisions {
    fun record(who: Asker, relation: Relation, obj: String, answer: Answer)

    companion object {
        /** Kept nowhere: for a test, or a service not yet publishing. */
        val none = Decisions { _, _, _, _ -> }
    }
}

/** The brokers, Apicurio's API (…/apis/registry/v3), and the service's own Kafka user, if the brokers have users. */
data class DecisionSettings(
    val bootstrap: String,
    val registry: String,
    val username: String? = null,
    val password: String? = null,
    val replication: Short = 1,
) {
    internal val client: Map<String, Any>
        get() = mapOf<String, Any>("bootstrap.servers" to bootstrap) + (username?.let { user ->
            mapOf(
                "security.protocol" to "SASL_PLAINTEXT",
                "sasl.mechanism" to "SCRAM-SHA-512",
                "sasl.jaas.config" to "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                    "username=\"$user\" password=\"${password.orEmpty()}\";",
            )
        } ?: emptyMap())
}

/**
 * Decisions on [DECISIONS], keyed by the object asked about, as every estate topic is: Protobuf, its schema in
 * Apicurio under FULL_TRANSITIVE, each record framed as Apicurio's deserializer reads it. Sent without waiting: a
 * decision is never held up by the record of it, and one that cannot be sent is logged.
 */
class KafkaDecisions(settings: DecisionSettings, private val service: String) : Decisions, AutoCloseable {
    private val log = LoggerFactory.getLogger("bank-access")
    private val contentId = register(settings.registry)
    private val producer: KafkaProducer<String, ByteArray>

    init {
        Admin.create(settings.client).use { admin ->
            try {
                admin.createTopics(listOf(NewTopic(DECISIONS, PARTITIONS, settings.replication))).all().get()
            } catch (failed: ExecutionException) {
                if (failed.cause !is TopicExistsException) throw failed
            }
        }
        producer = KafkaProducer(
            settings.client + mapOf("acks" to "all", "enable.idempotence" to true, "linger.ms" to 5),
            StringSerializer(),
            ByteArraySerializer(),
        )
    }

    override fun record(who: Asker, relation: Relation, obj: String, answer: Answer) {
        val decision = AccessDecision.newBuilder()
            .setId(UUID.randomUUID().toString()).setAtMillis(System.currentTimeMillis()).setService(service)
            .setSubject(who.subject).addAllGroups(who.groups.sorted()).setRelation(relation.name).setObject(obj)
            .setAnswer(
                when (answer) {
                    Answer.Yes -> AccessDecision.Answer.ANSWER_YES
                    Answer.No -> AccessDecision.Answer.ANSWER_NO
                    is Answer.Unanswered -> AccessDecision.Answer.ANSWER_UNANSWERED
                },
            )
        who.actor?.let(decision::setActor)
        who.address?.let(decision::setAddress)
        (answer as? Answer.Unanswered)?.let { decision.unansweredBecause = it.because }
        producer.send(ProducerRecord(DECISIONS, obj, framed(decision.build()))) { _, failed ->
            if (failed != null) log.warn("a decision about {} was not kept: {}", obj, failed.message)
        }
    }

    override fun close() = producer.close()

    private fun framed(decision: AccessDecision): ByteArray {
        val out = ByteArrayOutputStream(decision.serializedSize + FRAMING)
        out.write(0)
        out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(contentId).array())
        out.write(0)
        decision.writeTo(out)
        return out.toByteArray()
    }

    private companion object {
        const val PARTITIONS = 12
        const val FRAMING = 6
        const val SCHEMA = "bank/events/v1/decision.proto"
        const val ARTIFACT = "$DECISIONS-value"

        /** The topic's schema in Apicurio, found if it is there, and held to FULL_TRANSITIVE: its content id. */
        fun register(registry: String): Int {
            val http = HttpClient.newHttpClient()
            val nodes = JsonNodeFactory.instance
            fun send(path: String, body: String) = http.send(
                HttpRequest.newBuilder(URI.create(registry.trimEnd('/') + path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val schema = requireNotNull(Decisions::class.java.classLoader.getResourceAsStream(SCHEMA)) { "$SCHEMA is not on the classpath" }
                .use { String(it.readAllBytes()) }
            val body = nodes.objectNode().put("artifactId", ARTIFACT).put("artifactType", "PROTOBUF")
            body.putObject("firstVersion").putObject("content").put("content", schema).put("contentType", "application/x-protobuf")
            val created = send("/groups/default/artifacts?ifExists=FIND_OR_CREATE_VERSION", body.toString())
            check(created.statusCode() in 200..299) { "registering $ARTIFACT: ${created.statusCode()} ${created.body()}" }
            val rule = nodes.objectNode().put("ruleType", "COMPATIBILITY").put("config", "FULL_TRANSITIVE")
            val set = send("/groups/default/artifacts/$ARTIFACT/rules", rule.toString())
            check(set.statusCode() in 200..299 || set.statusCode() == 409) { "rule on $ARTIFACT: ${set.statusCode()} ${set.body()}" }
            return ObjectMapper().readTree(created.body()).path("version").path("contentId").asInt()
        }
    }
}
