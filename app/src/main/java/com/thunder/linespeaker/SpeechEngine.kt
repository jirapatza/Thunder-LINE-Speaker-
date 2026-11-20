package com.thunder.linespeaker

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Shared local Thai TTS. NotificationListenerService can call this when the
 * Activity is closed. No additional foreground service or audio-focus request
 * is started in the background.
 *
 * TextToSpeech initialization is asynchronous and some engines can callback
 * before the constructor returns. Always dispatch the callback to the main
 * looper so engine has been assigned before handling it.
 */
object SpeechEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = PendingSpeechQueue()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var generation = 0L
    private var utteranceSequence = 0L

    @Synchronized
    fun speak(context: Context, phrase: String, key: String, dedupeMs: Long = 90_000L) {
        val app = context.applicationContext
        if (!pending.offer(phrase, key, SystemClock.elapsedRealtime(), dedupeMs)) return
        if (ready) {
            drain(app)
        } else if (engine == null) {
            initialize(app)
        }
    }

    @Synchronized
    private fun initialize(context: Context) {
        val thisGeneration = ++generation
        VoiceHealth.ttsState(context, "กำลังเริ่มเครื่องอ่านเสียง")
        try {
            engine = TextToSpeech(context) { status ->
                // Avoid racing TextToSpeech's constructor/assignment.
                handler.post { finishInitialization(context, status, thisGeneration) }
            }
        } catch (_: RuntimeException) {
            engine = null
            ready = false
            VoiceHealth.speechFailed(context, "เปิดเครื่องเสียงไม่สำเร็จ")
        }
    }

    @Synchronized
    private fun finishInitialization(context: Context, status: Int, startedFor: Long) {
        if (startedFor != generation) return
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            reset(context, "เครื่องอ่านเสียงเริ่มไม่สำเร็จ")
            return
        }
        try {
            val language = tts.setLanguage(Locale.forLanguageTag("th-TH"))
            if (language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                reset(context, "ไม่มีเสียงภาษาไทยในเครื่อง")
                return
            }

            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    VoiceHealth.speechStarted(context)
                }

                override fun onDone(utteranceId: String) {
                    VoiceHealth.speechFinished(context)
                }

                override fun onError(utteranceId: String) {
                    VoiceHealth.speechFailed(context, "เครื่องเสียงอ่านไม่สำเร็จ")
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    VoiceHealth.speechFailed(context, "TTS รหัส $errorCode")
                }
            })
            ready = true
            VoiceHealth.ttsState(context, "พร้อมพูดภาษาไทย")
            drain(context)
        } catch (_: RuntimeException) {
            reset(context, "ตั้งค่าเสียงภาษาไทยไม่สำเร็จ")
        }
    }

    /** Called while holding this object's monitor. */
    private fun drain(context: Context) {
        val tts = engine ?: return
        // Do not read a notification hours later if a speech engine was absent.
        pending.discardStale(SystemClock.elapsedRealtime(), 120_000L)
        while (ready) {
            val next = pending.peek() ?: return
            val result = try {
                tts.speak(
                    next.phrase,
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "thunder-" + (++utteranceSequence)
                )
            } catch (_: RuntimeException) {
                TextToSpeech.ERROR
            }
            if (result == TextToSpeech.SUCCESS) {
                // Accepted by TTS, not a guarantee that the user heard audio.
                pending.markAccepted(SystemClock.elapsedRealtime())
            } else {
                reset(context, "ส่งข้อความเข้าเครื่องอ่านเสียงไม่สำเร็จ")
                return
            }
        }
    }

    @Synchronized
    private fun reset(context: Context, reason: String) {
        ready = false
        generation++
        val old = engine
        engine = null
        try {
            old?.shutdown()
        } catch (_: RuntimeException) {
            // An unavailable or crashed TTS engine must not crash the listener.
        }
        VoiceHealth.speechFailed(context, reason)
        // Keep recent pending phrases; the next notification retries TTS.
    }
}
