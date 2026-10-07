package bank.access.fga

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * One relationship: [user] has [relation] to [obj], until [expires] when it is a grant (the model's `not_expired`
 * condition), for good when it is not.
 */
data class Tuple(val user: String, val relation: String, val obj: String, val expires: Instant? = null) {
    /** What OpenFGA keys a relationship by: a grant written again with a later expiry is the same relationship. */
    val key: String get() = "$user $relation $obj"
}

/** OpenFGA refused or could not answer: the caller decides whether that is a retry or a refusal. */
class FgaUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * OpenFGA's HTTP API at [url], as the estate speaks it (lark-bank spec 0022): the store named [store], its newest model,
 * and a preshared [token] for whoever is calling. Every call blocks for at most [timeout]. [carried] gives the headers a
 * call takes from the calling thread, a trace's `traceparent` among them (lark-bank spec 0024), so OpenFGA's own spans
 * are in the caller's trace; this module asks for no tracing library to give them.
 */
class Fga(
    private val url: String,
    private val token: String,
    val store: String,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val carried: () -> Map<String, String> = { emptyMap() },
) {
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()

    /** Found once, by name: the store the access-model Job makes. */
    val storeId: String by lazy {
        stores(url, token, http, timeout, carried())[store] ?: throw FgaUnavailable("OpenFGA has no store named $store")
    }

    /** Whether [user] has [relation] to [obj] at [now], which is what a grant's expiry is weighed against. */
    fun check(user: String, relation: String, obj: String, now: Instant = Instant.now()): Boolean {
        val body = json.createObjectNode()
        body.putObject("tuple_key").put("user", user).put("relation", relation).put("object", obj)
        body.putObject("context").put("current_time", now.toString())
        return call("POST", "/stores/$storeId/check", body).path("allowed").asBoolean(false)
    }

    /** Every [type] object [user] has [relation] to, at [now]. */
    fun listObjects(user: String, relation: String, type: String, now: Instant = Instant.now()): Set<String> {
        val body = json.createObjectNode().put("user", user).put("relation", relation).put("type", type)
        body.putObject("context").put("current_time", now.toString())
        return call("POST", "/stores/$storeId/list-objects", body).path("objects").map(JsonNode::asText).toSet()
    }

    /** Writes [tuples], a hundred at a time, as OpenFGA takes them; one already there is left as it is. */
    fun write(tuples: Collection<Tuple>) = tuples.chunked(MOST_PER_WRITE).forEach { some ->
        val body = json.createObjectNode()
        val writes = body.putObject("writes").put("on_duplicate", "ignore")
        val keys = writes.putArray("tuple_keys")
        some.forEach { keys.add(it.toJson()) }
        call("POST", "/stores/$storeId/write", body)
    }

    /** Deletes [tuples], a hundred at a time; one already gone is no error. */
    fun delete(tuples: Collection<Tuple>) = tuples.chunked(MOST_PER_WRITE).forEach { some ->
        val body = json.createObjectNode()
        val deletes = body.putObject("deletes").put("on_missing", "ignore")
        val keys = deletes.putArray("tuple_keys")
        some.forEach { keys.add(json.createObjectNode().put("user", it.user).put("relation", it.relation).put("object", it.obj)) }
        call("POST", "/stores/$storeId/write", body)
    }

    /**
     * Every relationship in the store, or those matching what is given, a page at a time. OpenFGA reads by an object,
     * with or without a relation and a user, or by a user and an object's type (`account:`); not by a relation alone.
     */
    fun read(user: String? = null, relation: String? = null, obj: String? = null): List<Tuple> {
        val read = mutableListOf<Tuple>()
        var continuation = ""
        do {
            val body = json.createObjectNode().put("page_size", 100)
            if (continuation.isNotEmpty()) body.put("continuation_token", continuation)
            if (user != null || relation != null || obj != null) {
                val key = body.putObject("tuple_key")
                user?.let { key.put("user", it) }
                relation?.let { key.put("relation", it) }
                obj?.let { key.put("object", it) }
            }
            val page = call("POST", "/stores/$storeId/read", body)
            page.path("tuples").forEach { found ->
                val key = found.path("key")
                val expires = key.path("condition").path("context").path("expires").takeIf { !it.isMissingNode }?.asText()
                read += Tuple(key.path("user").asText(), key.path("relation").asText(), key.path("object").asText(), expires?.let(Instant::parse))
            }
            continuation = page.path("continuation_token").asText("")
        } while (continuation.isNotEmpty())
        return read
    }

    private fun Tuple.toJson(): ObjectNode {
        val key = json.createObjectNode().put("user", user).put("relation", relation).put("object", obj)
        expires?.let { key.putObject("condition").put("name", "not_expired").putObject("context").put("expires", it.toString()) }
        return key
    }

    private fun call(method: String, path: String, body: JsonNode?): JsonNode =
        send(http, url, token, timeout, method, path, body, carried())

    companion object {
        private val json = ObjectMapper()
        private const val MOST_PER_WRITE = 100

        /** Makes the store [store] if there is none, and writes [modelJson] as its newest model, as the Job does. */
        fun create(url: String, token: String, store: String, modelJson: String) {
            val http = HttpClient.newHttpClient()
            val timeout = Duration.ofSeconds(10)
            val id = stores(url, token, http, timeout)[store]
                ?: send(http, url, token, timeout, "POST", "/stores", json.createObjectNode().put("name", store)).path("id").asText()
            send(http, url, token, timeout, "POST", "/stores/$id/authorization-models", json.readTree(modelJson))
        }

        private fun stores(
            url: String,
            token: String,
            http: HttpClient,
            timeout: Duration,
            headers: Map<String, String> = emptyMap(),
        ): Map<String, String> {
            val found = mutableMapOf<String, String>()
            var continuation = ""
            do {
                val page = send(http, url, token, timeout, "GET", "/stores?page_size=100&continuation_token=$continuation", null, headers)
                page.path("stores").forEach { found.putIfAbsent(it.path("name").asText(), it.path("id").asText()) }
                continuation = page.path("continuation_token").asText("")
            } while (continuation.isNotEmpty())
            return found
        }

        private fun send(
            http: HttpClient,
            url: String,
            token: String,
            timeout: Duration,
            method: String,
            path: String,
            body: JsonNode?,
            headers: Map<String, String> = emptyMap(),
        ): JsonNode {
            val request = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + path)).timeout(timeout)
                .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
            val answer = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (failed: IOException) {
                throw FgaUnavailable("OpenFGA did not answer $method $path: ${failed.message}", failed)
            }
            if (answer.statusCode() !in 200..299) throw FgaUnavailable("OpenFGA answered $method $path with ${answer.statusCode()}: ${answer.body()}")
            return json.readTree(answer.body().ifEmpty { "{}" })
        }
    }
}
