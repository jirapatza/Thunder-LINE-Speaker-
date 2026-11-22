package com.thunder.linespeaker

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VerifiedPushIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun message(id: String, type: String = "payment_matched", amount: String = "260.00", time: Long = System.currentTimeMillis()): RemoteMessage =
        RemoteMessage.Builder("test-sender").setData(mapOf(
            "type" to type, "eventId" to id, "amount" to amount, "eventTime" to time.toString()
        )).build()
    private fun recorded(): List<String> = context.getSharedPreferences("verified_payment_spoken", Context.MODE_PRIVATE)
        .getString("ids", "").orEmpty().split(",").filter { it.isNotEmpty() }

    @Before fun reset() {
        context.getSharedPreferences("speaker_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("verified_payment_spoken", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun verifiedPushIsRecordedOnceWhileUnverifiedAndBadDataAreIgnored() {
        val service = Robolectric.buildService(LineSlipPushService::class.java).create().get()
        service.onMessageReceived(message("101"))
        service.onMessageReceived(message("101"))
        assertEquals(listOf("101"), recorded())
        service.onMessageReceived(message("102", type = "slip_unmatched"))
        service.onMessageReceived(message("103", amount = "1e2"))
        service.onMessageReceived(message("104", time = System.currentTimeMillis() - 700_000))
        assertEquals(listOf("101"), recorded())
    }

    @Test fun disabledVerifiedSpeechDoesNotRecordEvent() {
        assertTrue(Preferences.saveSpeech(context, SpeechType.VERIFIED, false, "รับเงินแล้ว {ยอดเงิน} บาท"))
        val service = Robolectric.buildService(LineSlipPushService::class.java).create().get()
        service.onMessageReceived(message("201"))
        assertTrue(recorded().isEmpty())
    }
}
