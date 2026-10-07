package bank.app

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context

/**
 * The trace the calling thread is in, as the W3C headers that carry it (bank spec 0024): for a call made with the JDK's
 * client rather than Pelican's, which traces its own. Empty outside a trace.
 */
fun traceHeaders(): Map<String, String> {
    val headers = HashMap<String, String>(2)
    W3CTraceContextPropagator.getInstance().inject(Context.current(), headers) { carrier, key, value -> carrier?.put(key, value) }
    return headers
}
