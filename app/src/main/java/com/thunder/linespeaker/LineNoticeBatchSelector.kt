package com.thunder.linespeaker

/** One observable item in an Android MessagingStyle notification. */
internal data class LineNoticeItem(
    val sender: String,
    val body: String,
    val hasPicture: Boolean,
    val timestampMs: Long
)

/**
 * LINE can attach many historical messages to a newly posted notification.
 * Read all recent items (up to a small bounded batch), not just the last one.
 * Skip old history when access is reconnected after a long pause.
 */
internal object LineNoticeBatchSelector {
    fun fresh(
        items: List<LineNoticeItem>,
        nowMs: Long,
        maxAgeMs: Long = 120_000L,
        maxFutureMs: Long = 60_000L,
        maxBatchSize: Int = 10
    ): List<LineNoticeItem> {
        if (items.isEmpty() || maxBatchSize <= 0) return emptyList()
        val tail = items.takeLast(maxBatchSize)
        // A timestamp of 0 means we cannot safely distinguish older history.
        // Only announce the latest in this situation instead of the full batch.
        if (tail.any { it.timestampMs <= 0 }) {
            val last = tail.last()
            // An item with a missing timestamp is impossible to order against
            // old history. Read only that item. However, if the last item does
            // have a timestamp, it must still be fresh.
            return if (last.timestampMs <= 0L ||
                (last.timestampMs >= nowMs - maxAgeMs &&
                    last.timestampMs <= nowMs + maxFutureMs)
            ) listOf(last) else emptyList()
        }
        val recent = tail.filter {
            it.timestampMs <= nowMs + maxFutureMs &&
                it.timestampMs >= nowMs - maxAgeMs
        }
        val selected = ArrayList<LineNoticeItem>(recent.size)
        for (item in recent) {
            // A caption and its media attachment may arrive as separate
            // MessagingStyle parts at the same instant. Merge only a text
            // part with a media part, NOT two distinct text messages that
            // happen to share the same millisecond timestamp.
            val matching = selected.indexOfLast { previous ->
                previous.sender == item.sender &&
                    previous.timestampMs == item.timestampMs &&
                    previous.hasPicture != item.hasPicture &&
                    (previous.body.isBlank() || item.body.isBlank() ||
                        previous.body == item.body)
            }
            if (matching < 0) {
                selected.add(item)
            } else {
                val previous = selected[matching]
                val combinedText = when {
                    previous.body.isBlank() -> item.body
                    item.body.isBlank() || previous.body == item.body -> previous.body
                    else -> previous.body + " " + item.body
                }
                selected[matching] = previous.copy(
                    body = combinedText, hasPicture = true
                )
            }
        }
        return selected
    }
}
