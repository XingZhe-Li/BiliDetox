package com.github.xingzheli.bilidetox.hook

/** Validate the first page's first ten videos; exemption is independently limited to five. */
class SearchResultGate<T> {
    enum class Status { ALLOWED, BLOCKED }
    data class Entry<T>(val value: T, val isVideo: Boolean, val matches: Boolean)
    data class Decision<T>(val items: List<T>, val status: Status)
    private data class Ranked<T>(val entry: Entry<T>, val videoRank: Int)
    private var status: Status? = null
    private var seenVideos = 0
    private val pages = object : LinkedHashMap<Int, Decision<T>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Decision<T>>?): Boolean = size > 16
    }

    @Synchronized
    fun accept(page: Int, entries: List<Entry<T>>): Decision<T> {
        pages[page]?.let { return it }
        if (status == null) {
            // A short first page is complete for validation, regardless of a next-page cursor.
            status = if (page == 1 && entries.asSequence().filter { it.isVideo }
                .take(VALIDATION_VIDEOS).any { it.matches }) Status.ALLOWED else Status.BLOCKED
        }
        val decisionStatus = status!!
        if (decisionStatus == Status.BLOCKED) return Decision<T>(emptyList(), decisionStatus)
            .also { pages[page] = it }
        val ranked = entries.map { entry ->
            Ranked(entry, if (entry.isVideo) ++seenVideos else 0)
        }
        val kept = ranked.filter {
            !it.entry.isVideo || it.videoRank <= EXEMPT_VIDEOS || it.entry.matches
        }.map { it.entry.value }
        return Decision(kept, decisionStatus).also { pages[page] = it }
    }

    companion object {
        const val VALIDATION_VIDEOS = 10
        const val EXEMPT_VIDEOS = 5
    }
}
