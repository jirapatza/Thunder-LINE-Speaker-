package com.thunder.linespeaker

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * One Thai TTS engine shared by LINE text, OCR and verified payment messages.
 * Android initializes TTS asynchronously; retain every distinct speech request
 * in FIFO order rather than overwriting earlier messages with a single pending
 * string. Acceptance by TTS is NOT proof audio reached the device's speaker.
 */
object SpeechEngine {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var utteranceSequence = 0L
    private val pending = PendingSpeechQueue()

    @Synchronized
    fun speak(context: Context, phrase: String, key: String, dedupeMs: Long = 90_000L) {
        if (!pending.offer(phrase, key, SystemClock.elapsedRealtime(), dedupeMs)) return
        if (ready) {
            drain()
        } else if (engine == null) {
            initialize(context.applicationContext)
        }
    }

    private fun initialize(context: Context) {
        engine = TextToSpeech(context) { status ->
            synchronized(this) {
                val tts = engine
                if (tts != null && status == TextToSpeech.SUCCESS) {
                    val language = tts.setLanguage(Locale("th", "TH"))
                    ready = language != TextToSpeech.LANG_MISSING_DATA &&
                        language != TextToSpeech.LANG_NOT_SUPPORTED
                } else {
                    ready = false
                }
                if (ready) {
                    drain()
                } else {
                    // Keep the queue if the TTS engine fails; a later incoming
                    // message can attempt to initialize the engine again.
                    tts?.shutdown()
                    engine = null
                }
            }
        }
    }

    /** Called while holding the same monitor as speak(). */
    private fun drain() {
        val tts = engine ?: return
        while (ready) {
            val next = pending.peek() ?: return
            val result = tts.speak(
                next.phrase,
                TextToSpeech.QUEUE_ADD,
                null,
                "thunder-${++utteranceSequence}",
            )
            if (result == TextToSpeech.SUCCESS) {
                pending.markAccepted(SystemClock.elapsedRealtime())
            } else {
                // Do not record this event as already spoken if TTS rejected it.
                ready = false
                tts.shutdown()
                engine = null
                return
            }
        }
    }
}
