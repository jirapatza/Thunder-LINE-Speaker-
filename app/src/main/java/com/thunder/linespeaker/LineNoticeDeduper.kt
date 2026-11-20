package com.thunder.linespeaker

/**
 * Keep a bounded set of recent message fingerprints per Android notification.
 * Unlike tracking only the latest fingerprint, this also stops older entries
 * in a MessagingStyle batch from being read again after newer entries arrive.
 * 190 seconds covers the 120-second incoming-message window plus the 60-second
 * allowed sender-clock skew, so a still-eligible item cannot replay merely
 * because the local dedupe entry expired.
 */
internal class LineNoticeDeduper(
    private val ttlMs: Long = 190_000L,
    private val capacity: Int = 512
) {
    private val seen = LinkedHashMap<Pair<String, String>, Long>()

    @Synchronized
    fun accept(notificationKey: String, fingerprint: String, nowMs: Long): Boolean {
        if (notificationKey.isBlank() || fingerprint.isBlank() || capacity <= 0) return false

        seen.entries.removeAll {
            nowMs < it.value || nowMs - it.value >= ttlMs
        }
        val key = Pair(notificationKey, fingerprint)
        if (seen.containsKey(key)) return false

        seen[key] = nowMs
        while (seen.size > capacity) seen.remove(seen.keys.first())
        return true
    }
}
