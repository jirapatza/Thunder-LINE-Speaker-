package com.thunder.linespeaker

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeechSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("speaker_settings", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun defaultsAndIndependentlyPersistedSettings() {
        assertTrue(Preferences.speechEnabled(context, SpeechType.CUSTOMER))
        assertTrue(Preferences.speechEnabled(context, SpeechType.PICTURE))
        assertTrue(Preferences.speechEnabled(context, SpeechType.VERIFIED))
        assertEquals("มีลูกค้าทักมาใหม่", Preferences.speech(context, SpeechType.CUSTOMER))
        assertEquals("ลูกค้าโอนเงินแล้ว", Preferences.speech(context, SpeechType.PICTURE))
        assertTrue(Preferences.saveSpeech(context, SpeechType.CUSTOMER, false, " สวัสดี "))
        assertTrue(Preferences.saveSpeech(context, SpeechType.PICTURE, true, "ภาพใหม่"))
        assertNull(Preferences.speech(context, SpeechType.CUSTOMER))
        assertEquals("สวัสดี", Preferences.speechText(context, SpeechType.CUSTOMER))
        assertEquals("ภาพใหม่", Preferences.speech(context, SpeechType.PICTURE))
        // Reopening preferences reads persisted values, including legacy toggles.
        assertFalse(context.getSharedPreferences("speaker_settings", Context.MODE_PRIVATE).getBoolean("customers", true))
        assertFalse(Preferences.customers(context))
        assertTrue(Preferences.payments(context))
    }

    @Test fun verifiedRequiresConfirmedAmountAndValidTemplate() {
        assertEquals("รับเงินแล้ว 260 บาท", Preferences.speech(context, SpeechType.VERIFIED, BigDecimal("260.00")))
        assertNull(Preferences.speech(context, SpeechType.VERIFIED))
        assertNull(Preferences.speech(context, SpeechType.VERIFIED, BigDecimal.ZERO))
        assertFalse(Preferences.saveSpeech(context, SpeechType.VERIFIED, true, "รับเงินแล้ว"))
        assertFalse(Preferences.saveSpeech(context, SpeechType.VERIFIED, true, "รับ 999 {ยอดเงิน} บาท"))
        assertTrue(Preferences.saveSpeech(context, SpeechType.VERIFIED, true, "ยอด {ยอดเงิน} บาท"))
        assertEquals("ยอด 260 บาท", Preferences.speech(context, SpeechType.VERIFIED, BigDecimal("260")))
        assertTrue(Preferences.saveSpeech(context, SpeechType.VERIFIED, false, "ยอด {ยอดเงิน} บาท"))
        assertNull(Preferences.speech(context, SpeechType.VERIFIED, BigDecimal("260")))
    }
}
