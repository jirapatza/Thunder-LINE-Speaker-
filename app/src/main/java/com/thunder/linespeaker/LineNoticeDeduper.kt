package com.thunder.linespeaker

/**
 * Suppress repeated Android notification updates without suppressing a new
 * LINE message that happens to contain the same text. The caller includes the
 * message timestamp, where available, in the fingerprint.
 */
internal class LineNoticeDeduper(
    private val ttlMs: Long = 120_000L,
    private val capacity: Int = 256
) {
    private data class Entry(val fingerprint: String, val atMs: Long)
    private val seen = LinkedHashMap<String, Entry>()

    @Synchronized
    fun accept(notificationKey: String, fingerprint: String, nowMs: Long): Boolean {
        if (notificationKey.isBlank() || fingerprint.isBlank() || capacity <= 0) return false

        seen.entries.removeAll {
            nowMs < it.value.atMs || nowMs - it.value.atMs >= ttlMs
        }
        val previous = seen[notificationKey]
        if (previous != null && previous.fingerprint == fingerprint) return false

        seen.remove(notificationKey)
        seen[notificationKey] = Entry(fingerprint, nowMs)
        while (seen.size > capacity) seen.remove(seen.keys.first())
        return true
    }
}
