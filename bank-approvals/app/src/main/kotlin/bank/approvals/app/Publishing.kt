package bank.approvals.app

import bank.approvals.domain.RequestEvent
import bank.approvals.domain.RequestId
import bank.approvals.protocol.Kinds
import bank.approvals.protocol.RequestEvents
import bank.approvals.protocol.RequestHead
import bank.approvals.protocol.head
import bank.approvals.protocol.toContract
import bank.events.v1.ApprovalEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.OffsetStore
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.SlicedFeed
import io.github.matthewjones372.lark.actor.projection.Projection
import io.github.matthewjones372.lark.actor.projection.runProjecting
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.kafka.Kafka
import io.github.matthewjones372.lark.kafka.Producer
import io.github.matthewjones372.lark.kafka.carrying
import io.github.matthewjones372.lark.kafka.producer
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.stream.mapAsync
import io.github.matthewjones372.lark.stream.restartOnDefect
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * Every request's events on Kafka (lark-bank spec 0019), in the bank's published language (spec 0015): the schema in
 * lark-bank-events, registered in Apicurio under FULL_TRANSITIVE, and each record framed as Apicurio's and Confluent's
 * deserializers read it. A projection follows the journal and publishes each event once it is written; the owning
 * service applies on `ApprovalGiven` or `AutoApproved`.
 */

/** The topic, and what fixes its shape. */
object ApprovalTopic {
    const val NAME = "bank.approval-events"

    /** The schema's file in lark-bank-events, whose first message is the topic's. */
    const val SCHEMA = "bank/events/v1/approval.proto"

    /** Fixed from the start and never changed: a change moves keys between partitions and breaks their order. */
    const val PARTITIONS = 12

    /** The registry's name for what the topic's values are, as Apicurio's and Confluent's default strategy names it. */
    const val ARTIFACT = "$NAME-value"

    /** The read model's name: what its offsets are saved under. */
    const val PUBLISHED = "published-requests"
}

/**
 * Sends each [ApprovalEvent] keyed by its request, so a request's events stay in order. A record's value is a zero
 * byte, the schema's content id in the registry (4 bytes), the message's index in its file (one zero byte), then the
 * message.
 */
class ApprovalEvents(private val producer: Producer<String, ByteArray>, private val contentId: Int) : AutoCloseable {
    /**
     * Completes once the brokers have it for good (acks=all). [carried] goes as headers: the trace the event was written
     * in, so whoever handles it continues that trace (lark-bank spec 0024).
     */
    fun publish(event: ApprovalEvent, carried: Map<String, String> = emptyMap()): CompletableFuture<Unit> =
        producer.send(ProducerRecord(ApprovalTopic.NAME, event.requestId, framed(contentId, event)).carrying(carried)).thenApply { }

    override fun close() = producer.close()

    companion object {
        fun framed(contentId: Int, event: ApprovalEvent): ByteArray {
            val out = ByteArrayOutputStream(event.serializedSize + FRAMING)
            out.write(0)
            out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(contentId).array())
            out.write(0)
            event.writeTo(out)
            return out.toByteArray()
        }

        private const val FRAMING = 6
    }
}

/** A schema the registry refused: it breaks one already there, and nothing is published until that is fixed. */
class SchemaRefused(artifact: String, why: String) : IllegalStateException("the registry refused $artifact: $why")

/** Apicurio's REST API, v3, at [url] (…/apis/registry/v3): just what registering the topic's schema takes. */
class Apicurio(private val url: String, private val group: String = "default") {
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private val nodes = JsonNodeFactory.instance

    /**
     * The topic's schema as a version of its artifact, held to FULL_TRANSITIVE from the first, and its content id.
     * Already there, it is found, not added; changed, it becomes a new version only if it reads, and is read by, every
     * version before it, or the registry refuses it and so does this.
     */
    fun register(): Int {
        val artifact = ApprovalTopic.ARTIFACT
        val body = nodes.objectNode().put("artifactId", artifact).put("artifactType", "PROTOBUF")
        body.putObject("firstVersion").putObject("content")
            .put("content", resource(ApprovalTopic.SCHEMA)).put("contentType", "application/x-protobuf")
        val created = send("POST", "/groups/$group/artifacts?ifExists=FIND_OR_CREATE_VERSION", body.toString())
        // A version the compatibility rule turns away is a 400 naming each break, or a 409.
        if (created.statusCode() == BAD_REQUEST || created.statusCode() == CONFLICT) throw SchemaRefused(artifact, created.body())
        check(created.statusCode() in OK) { "registering $artifact: ${created.statusCode()} ${created.body()}" }
        val rule = nodes.objectNode().put("ruleType", "COMPATIBILITY").put("config", "FULL_TRANSITIVE")
        val set = send("POST", "/groups/$group/artifacts/$artifact/rules", rule.toString())
        check(set.statusCode() in OK || set.statusCode() == CONFLICT) { "rule on $artifact: ${set.statusCode()} ${set.body()}" }
        return json.readTree(created.body()).path("version").path("contentId").asInt()
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

/** The topic, made with [ApprovalTopic.PARTITIONS] partitions if it is not there yet. */
fun createTopic(settings: KafkaSettings) = Admin.create(settings.client).use { admin ->
    try {
        admin.createTopics(listOf(NewTopic(ApprovalTopic.NAME, ApprovalTopic.PARTITIONS, settings.replication))).all().get()
    } catch (failed: ExecutionException) {
        if (failed.cause !is TopicExistsException) throw failed
    }
}

/** The publisher: the schema registered and the topic made first, so it can publish. */
fun approvalEvents(settings: KafkaSettings): ApprovalEvents {
    val contentId = Apicurio(settings.registry).register()
    createTopic(settings)
    val producer = Kafka.producer(
        settings.client + mapOf<String, Any>(
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.LINGER_MS_CONFIG to 5,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ),
        key = StringSerializer(),
        value = ByteArraySerializer(),
    )
    return ApprovalEvents(producer, contentId)
}

/**
 * Each request's kind and subject, which only its `Requested` names: remembered from that event as it passes, and read
 * from the journal for a request whose `Requested` was published before this publisher started.
 */
class RequestHeads(private val journal: Journal, private val remembered: Int = 10_000) {
    private val known = object : LinkedHashMap<String, RequestHead>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RequestHead>) = size > remembered
    }

    fun of(id: RequestId, event: RequestEvent): RequestHead = synchronized(known) {
        if (event is RequestEvent.Requested) known[id.value] = event.head()
        known.getOrPut(id.value) { requested(id) }
    }

    private fun requested(id: RequestId): RequestHead {
        val first = journal.read(PersistenceId(Kinds.REQUEST, id.value)).firstOrNull()
            ?.let { stored -> RequestEvents.decode(stored.bytes).event }
        check(first is RequestEvent.Requested) { "request ${id.value} does not begin with its Requested" }
        return first.head()
    }
}

/** A projection that throws (the database or the brokers away for a moment) starts again from its last saved offset. */
private val again = Schedule.spaced<Throwable>(1.seconds)

/** Events waiting on the brokers at once, so the producer can batch them. */
private const val IN_FLIGHT = 64

/**
 * Share [share] of [shares] of every request event in [feed], published in order. An offset is saved only once the
 * brokers have every event up to it, so a restart sends the rest again and never skips one.
 */
fun publishedRequests(
    feed: SlicedFeed,
    offsets: OffsetStore,
    events: ApprovalEvents,
    heads: RequestHeads,
    share: Int,
    shares: Int,
    every: Duration,
) = Projection.partitioned(feed, Kinds.REQUEST, RequestEvents, offsets, ApprovalTopic.PUBLISHED, share, shares, every)
    .mapAsync(IN_FLIGHT) { followed ->
        val id = RequestId(followed.id.id)
        val event = followed.value.event
        events.publish(event.toContract(heads.of(id, event), followed.sequence), tracedOnly(followed.metadata)).thenApply {
            counter("approvals.published").increment()
            followed
        }
    }
    .restartOnDefect(again)
    .runProjecting()

/** Of what an event's append carried, only its trace: the rest is the writer's own, not the topic's readers'. */
fun tracedOnly(metadata: Map<String, String>): Map<String, String> = metadata.filterKeys { it == "traceparent" || it == "tracestate" }

/**
 * The owner asked again on Kafka: the request's `ApprovalGiven` or `AutoApproved` sent once more, under its own
 * sequence, which a consumer that has it already takes as a nudge to say `applied` again. Its place in the history is
 * its sequence: each event is one entry in the journal.
 */
class KafkaOwners(private val events: ApprovalEvents) : Owners {
    override fun askAgain(id: RequestId, history: List<RequestEvent>) {
        val requested = history.firstOrNull() as? RequestEvent.Requested ?: return
        val at = history.indexOfLast { it is RequestEvent.ApprovalGiven || it is RequestEvent.AutoApproved }
        if (at < 0) return
        events.publish(history[at].toContract(requested.head(), at + 1L)).whenComplete { _, failed ->
            if (failed != null) logWarn("request ${id.value}: asking its owner again failed: ${failed.message}")
        }
    }
}
