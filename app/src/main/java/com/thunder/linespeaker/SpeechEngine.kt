package com.thunder.linespeaker

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Shares a single Thai TTS engine between the notification and screen services. */
object SpeechEngine {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val recentlySpoken = LinkedHashMap<String, Long>()

    @Synchronized
    fun speak(context: Context, phrase: String, key: String, dedupeMs: Long = 90_000L) {
        val now = SystemClock.elapsedRealtime()
        val previous = recentlySpoken[key]
        if (previous != null && now - previous < dedupeMs) return
        recentlySpoken[key] = now
        recentlySpoken.entries.removeAll { now - it.value > 5 * 60_000L }
        val instance = engine
        if (instance == null) {
            pending = phrase
            engine = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    if (status == TextToSpeech.SUCCESS) {
                        val result = engine?.setLanguage(Locale("th", "TH"))
                        ready = result != TextToSpeech.LANG_MISSING_DATA &&
                            result != TextToSpeech.LANG_NOT_SUPPORTED
                        if (ready) pending?.let { engine?.speak(it, TextToSpeech.QUEUE_ADD, null, "initial") }
                    }
                    pending = null
                }
            }
        } else if (ready) {
            instance.speak(phrase, TextToSpeech.QUEUE_ADD, null, "speak-$now")
        } else {
            pending = phrase
        }
    }
}
