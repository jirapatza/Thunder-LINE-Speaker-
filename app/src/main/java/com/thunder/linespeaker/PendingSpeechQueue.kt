package com.thunder.linespeaker

/**
 * Bounded FIFO for speech requests arriving before Android's asynchronous Thai
 * TextToSpeech initialization has finished. Pure Kotlin so JVM unit tests can
 * verify rapid incoming LINE messages without an emulator.
 */
internal class PendingSpeechQueue(private val capacity: Int = 200) {
    data class Entry(val phrase: String, val key: String, val dedupeMs: Long, val queuedAtMs: Long)

    private val pending = ArrayDeque<Entry>()
    private val accepted = LinkedHashMap<String, Long>()

    fun offer(phrase: String, key: String, nowMs: Long, dedupeMs: Long): Boolean {
        if (key.isBlank() || phrase.isBlank() || capacity <= 0) return false
        val last = accepted[key]
        if (last != null && nowMs >= last && nowMs - last < dedupeMs) return false
        if (pending.any { it.key == key } || pending.size >= capacity) return false
        pending.addLast(Entry(phrase, key, dedupeMs, nowMs))
        return true
    }

    /**
     * A TTS engine may become ready long after a LINE event. Avoid speaking
     * hours-old messages if the phone temporarily had no Thai speech engine.
     * Unaccepted expired entries must not be recorded as successfully spoken.
     */
    fun discardStale(nowMs: Long, maxAgeMs: Long): Int {
        val before = pending.size
        pending.removeAll {
            nowMs < it.queuedAtMs || nowMs - it.queuedAtMs > maxAgeMs
        }
        return before - pending.size
    }

    fun peek(): Entry? = pending.firstOrNull()

    /** Commit deduplication only after TextToSpeech accepts the utterance. */
    fun markAccepted(nowMs: Long): Entry? {
        if (pending.isEmpty()) return null
        val item = pending.removeFirst()
        accepted[item.key] = nowMs
        // Keep in-process dedupe for one day; FCM separately persists IDs.
        accepted.entries.removeAll {
            nowMs >= it.value && nowMs - it.value > 86_400_000L
        }
        while (accepted.size > 2000) accepted.remove(accepted.keys.first())
        return item
    }

    fun size(): Int = pending.size
}
