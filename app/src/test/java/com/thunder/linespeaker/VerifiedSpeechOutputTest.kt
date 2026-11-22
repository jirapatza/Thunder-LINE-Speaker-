package com.thunder.linespeaker

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VerifiedSpeechOutputTest {
    @Test fun savedTemplateIsSpokenForVerifiedPushOnlyOnce() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("speaker_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("verified_payment_spoken", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(Preferences.saveSpeech(context, SpeechType.VERIFIED, true, "ยอดยืนยัน {ยอดเงิน} บาท"))
        // Robolectric resets its TTS shadow between test classes, while our
        // process-wide SpeechEngine singleton may still hold the old instance.
        SpeechEngine::class.java.getDeclaredMethod("reset", Context::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(SpeechEngine, context, "test reset")
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("th-TH"))
        val service = Robolectric.buildService(LineSlipPushService::class.java).create().get()
        fun event(id: String, type: String, amount: String) = RemoteMessage.Builder("sender").setData(mapOf(
            "type" to type, "eventId" to id, "amount" to amount,
            "eventTime" to System.currentTimeMillis().toString()
        )).build()

        service.onMessageReceived(event("501", "payment_matched", "260.00"))
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotNull(tts)
        val shadow = shadowOf(tts)
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("ยอดยืนยัน 260 บาท", shadow.lastSpokenText)
        val spokenCount = shadow.spokenTextList.size

        service.onMessageReceived(event("501", "payment_matched", "260.00"))
        service.onMessageReceived(event("502", "slip_unmatched", "260.00"))
        service.onMessageReceived(event("503", "payment_matched", "not-an-amount"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(spokenCount, shadow.spokenTextList.size)
    }
}
