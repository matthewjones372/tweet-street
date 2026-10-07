package bank.approvals.domain

/**
 * A request's events as a hash chain: each link is the SHA-256 of the one before and the event as stored, so editing
 * any stored event, or dropping or reordering one, breaks every link after it. The event's stored form is the
 * caller's; the chain only needs the same bytes each time.
 */
object Chain {
    /** The link for [event], stored after [previous]; null before the first. */
    fun link(previous: String?, event: String): String = sha256("${previous.orEmpty()}\n$event")

    /** A chain as stored: each event's text beside the link recorded for it. */
    data class Stored(val event: String, val link: String)

    /** The index of the first stored event whose link does not follow from the ones before it, or null when all do. */
    fun firstBroken(chain: List<Stored>): Int? {
        var previous: String? = null
        chain.forEachIndexed { index, stored ->
            if (link(previous, stored.event) != stored.link) return index
            previous = stored.link
        }
        return null
    }

    /** The links for [events], in order: what a writer stores beside them. */
    fun links(events: List<String>): List<String> =
        events.runningFold(null as String?) { previous, event -> link(previous, event) }.drop(1).map { it!! }
}
