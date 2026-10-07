package bank.api

import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.attempt
import io.github.matthewjones372.pelican.metrics.otel.otelContext
import java.util.concurrent.CompletableFuture

/**
 * A request finished with its trace current (bank spec 0024's `trace-views`). The request's span is current only while
 * the request thread runs, and the latency is recorded when the request completes, often on another thread; so this,
 * just inside the meters' filter, completes the request inside the trace's context, and the latency recorded there is
 * an exemplar of that trace. Nothing changes for a request no span was made for.
 */
val completedInTrace: Filter = Filter { params, next ->
    if (otelContext !in params) return@Filter attempt(params, next)
    val context = params[otelContext]
    val done = CompletableFuture<Any?>()
    attempt(params, next).whenComplete { result, error ->
        context.makeCurrent().use { if (error != null) done.completeExceptionally(error) else done.complete(result) }
    }
    done
}
