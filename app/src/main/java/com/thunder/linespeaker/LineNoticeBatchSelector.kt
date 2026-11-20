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
        if (tail.any { it.timestampMs <= 0 }) return listOf(tail.last())
        return tail.filter {
            it.timestampMs <= nowMs + maxFutureMs &&
                it.timestampMs >= nowMs - maxAgeMs
        }
    }
}
