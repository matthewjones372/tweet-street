package bank.protocol.wire

import kotlinx.serialization.Serializable

/**
 * What one node says of itself once a second on the `ops` topic (bank spec 0007): its status in its own view, the
 * shards and entities it holds by kind, and its rates over the last second. Totals only: nothing per account.
 */
@Serializable
data class NodeSnapshot(
    val node: String,
    val status: String,
    val atMillis: Long,
    val shards: Map<String, Long>,
    val entities: Map<String, Long>,
    val asksPerSecond: Double,
    val askP99Millis: Double,
    val commandsPerSecond: Map<String, Double>,
)
