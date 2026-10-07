package bank.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.google.protobuf.Message
import io.github.matthewjones372.lark.kafka.Kafka
import io.github.matthewjones372.lark.kafka.Producer
import io.github.matthewjones372.lark.kafka.producer
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import io.github.matthewjones372.lark.kafka.carrying

/*
 * Every event published (bank spec 0015): each topic's schema registered in Apicurio, and each event written in the
 * framing Apicurio's and Confluent's deserializers read. Kafka is the one transport; `Publisher` keeps the door open.
 */

/**
 * Where the bank's events go: a stream, a key within it that orders them, and the event; with [carried], the trace the
 * event was written in (bank spec 0024), sent on as headers so a consumer continues it.
 */
data class Outbound(val stream: String, val key: String, val value: Message, val carried: Map<String, String> = emptyMap())

/** What of an event's metadata other services are sent: its trace, and not the bank's own log annotations. */
fun tracedOnly(metadata: Map<String, String>): Map<String, String> = metadata.filterKeys { it == "traceparent" || it == "tracestate" }

interface Publisher : AutoCloseable {
    /** Sends [record]; the future completes once the transport has it for good (for Kafka, acks=all). */
    fun publish(record: Outbound): CompletableFuture<Unit>
}

/** The bank's topics, and the schema each carries: its file in lark-bank-events, whose first message is the topic's. */
enum class EventStream(val topic: String, val schema: String) {
    ACCOUNTS("bank.account-events", "bank/events/v1/account.proto"),
    TRANSFERS("bank.transfer-events", "bank/events/v1/transfer.proto"),
    ACCESS("bank.access-events", "bank/events/v1/access.proto"),
    ;

    /** The registry's name for what the topic's values are, as Apicurio's and Confluent's default strategy names it. */
    val artifact: String get() = "$topic-value"

    companion object {
        /** Fixed from the start and never changed: a change moves keys between partitions and breaks their order. */
        const val PARTITIONS = 12

        /** The file the account and transfer schemas import, registered first and referenced by name. */
        const val MONEY = "bank/events/v1/money.proto"
    }
}

/**
 * [Publisher] over Kafka: each record's value is a zero byte, the schema's content id in the registry (4 bytes), the
 * message's index in its file (one zero byte: each topic's message is first), then the message.
 */
class KafkaPublisher(private val producer: Producer<String, ByteArray>, private val schemas: Map<String, Int>) : Publisher {
    override fun publish(record: Outbound): CompletableFuture<Unit> {
        val schema = requireNotNull(schemas[record.stream]) { "no schema registered for ${record.stream}" }
        val sent = ProducerRecord(record.stream, record.key, framed(schema, record.value)).carrying(record.carried)
        return producer.send(sent).thenApply { }
    }

    override fun close() = producer.close()

    companion object {
        fun framed(contentId: Int, message: Message): ByteArray {
            val out = ByteArrayOutputStream(message.serializedSize + 6)
            out.write(0)
            out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(contentId).array())
            out.write(0)
            message.writeTo(out)
            return out.toByteArray()
        }
    }
}

/** A schema the registry refused: it breaks one already there, and nothing is published until that is fixed. */
class SchemaRefused(artifact: String, why: String) : IllegalStateException("the registry refused $artifact: $why")

/** Apicurio's REST API, v3, at [url] (…/apis/registry/v3): just what registering the bank's schemas takes. */
class Apicurio(private val url: String, private val group: String = "default") {
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private val nodes = JsonNodeFactory.instance

    /**
     * Registers the money file and every topic's schema, each held to FULL_TRANSITIVE, and answers each topic's content
     * id. Already there, a schema is found, not added; changed, it becomes a new version only if it reads, and is read
     * by, every version before it, or the registry refuses it and so does this.
     */
    fun registerAll(): Map<String, Int> {
        val money = register(EventStream.MONEY.replace('/', '.'), resource(EventStream.MONEY), references = emptyList())
        val reference = nodes.objectNode().put("groupId", group).put("artifactId", money.artifact)
            .put("version", money.version).put("name", EventStream.MONEY)
        return EventStream.entries.associate { stream ->
            val schema = resource(stream.schema)
            val references = if ("import \"${EventStream.MONEY}\"" in schema) listOf(reference) else emptyList()
            stream.topic to register(stream.artifact, schema, references).contentId
        }
    }

    data class Registered(val artifact: String, val version: String, val contentId: Int)

    /** [content] as a version of [artifact], found if it is already one, held to FULL_TRANSITIVE from the first. */
    fun register(artifact: String, content: String, references: List<JsonNode>): Registered {
        val body = nodes.objectNode().put("artifactId", artifact).put("artifactType", "PROTOBUF")
        body.putObject("firstVersion").putObject("content")
            .put("content", content).put("contentType", "application/x-protobuf")
            .putArray("references").addAll(references)
        val created = send("POST", "/groups/$group/artifacts?ifExists=FIND_OR_CREATE_VERSION", body.toString())
        // A version the compatibility rule turns away is a 400 naming each break, or a 409.
        if (created.statusCode() == BAD_REQUEST || created.statusCode() == CONFLICT) throw SchemaRefused(artifact, created.body())
        check(created.statusCode() in OK) { "registering $artifact: ${created.statusCode()} ${created.body()}" }
        val version = json.readTree(created.body()).path("version")
        compatibility(artifact)
        return Registered(artifact, version.path("version").asText(), version.path("contentId").asInt())
    }

    /** FULL_TRANSITIVE on [artifact], set once: the rule every later version is checked by. */
    private fun compatibility(artifact: String) {
        val rule = nodes.objectNode().put("ruleType", "COMPATIBILITY").put("config", "FULL_TRANSITIVE")
        val set = send("POST", "/groups/$group/artifacts/$artifact/rules", rule.toString())
        check(set.statusCode() in OK || set.statusCode() == CONFLICT) { "rule on $artifact: ${set.statusCode()} ${set.body()}" }
    }

    private fun send(method: String, path: String, body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(url.trimEnd('/') + path)).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "$path is not on the classpath" }.use { String(it.readAllBytes()) }

    private companion object {
        val OK = 200..299
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
    }
}

/** Every topic, made with [EventStream.PARTITIONS] partitions if they are not there yet. */
fun createTopics(settings: KafkaSettings) = Admin.create(settings.client).use { admin ->
    EventStream.entries.forEach { stream ->
        try {
            admin.createTopics(listOf(NewTopic(stream.topic, EventStream.PARTITIONS, settings.replication))).all().get()
        } catch (failed: ExecutionException) {
            if (failed.cause !is TopicExistsException) throw failed
        }
    }
}

/** The publisher the read models send through: the schemas registered and the topics made first, so it can publish. */
fun kafkaPublisher(settings: KafkaSettings): KafkaPublisher {
    val schemas = Apicurio(settings.registry).registerAll()
    createTopics(settings)
    val producer = Kafka.producer(
        settings.client + mapOf<String, Any>(
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.LINGER_MS_CONFIG to 5,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ),
        key = StringSerializer(),
        value = ByteArraySerializer(),
    )
    return KafkaPublisher(producer, schemas)
}
