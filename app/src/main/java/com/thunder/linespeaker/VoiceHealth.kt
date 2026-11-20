package com.thunder.linespeaker

import android.content.Context
import java.text.DateFormat
import java.util.Date

/**
 * Local troubleshooting counters only. Never persist LINE sender names,
 * notification bodies, payment amounts, or spoken phrases.
 */
internal object VoiceHealth {
    private const val FILE = "local_voice_health"
    @Volatile private var listenerBound = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun listenerConnected(context: Context) {
        listenerBound = true
        prefs(context).edit()
            .putLong("last_listener_connect", System.currentTimeMillis()).apply()
    }

    fun listenerDisconnected() {
        listenerBound = false
    }

    fun receivedLineNotice(context: Context) {
        prefs(context).edit().putLong("last_line_notice", System.currentTimeMillis()).apply()
    }

    fun ttsState(context: Context, state: String) {
        prefs(context).edit()
            .putString("tts_state", state)
            .putLong("last_tts_state", System.currentTimeMillis())
            .apply()
    }

    fun slipOcrStatus(context: Context, description: String) {
        // Status string is an enum-like fixed description, never the OCR data.
        prefs(context).edit()
            .putString("slip_ocr_status", description)
            .putLong("last_slip_ocr", System.currentTimeMillis())
            .apply()
    }

    fun speechStarted(context: Context) {
        prefs(context).edit().putLong("last_speech_start", System.currentTimeMillis()).apply()
    }

    fun speechFinished(context: Context) {
        prefs(context).edit().putLong("last_speech_finish", System.currentTimeMillis()).apply()
    }

    fun speechFailed(context: Context, reason: String) {
        prefs(context).edit()
            .putString("tts_state", "ผิดพลาด: $reason")
            .putLong("last_tts_state", System.currentTimeMillis())
            .apply()
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        val status = p.getString("tts_state", "ยังไม่ได้ทดสอบเสียง").orEmpty()
        fun time(name: String): String {
            val at = p.getLong(name, 0L)
            return if (at > 0) DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.MEDIUM
            ).format(Date(at)) else "ยังไม่มี"
        }
        val connection = if (listenerBound) "เชื่อมต่อในขณะนี้" else "ยังไม่พบการเชื่อมต่อในรอบนี้"
        return "ตัวอ่านแจ้งเตือน: $connection\n" +
            "เชื่อมต่อล่าสุด: " + time("last_listener_connect") + "\n" +
            "แจ้งเตือน LINE OA ที่รับล่าสุด: " + time("last_line_notice") + "\n" +
            "สถานะเสียงล่าสุด: $status\n" +
            "สถานะอ่านยอดจากภาพ: " + p.getString("slip_ocr_status", "ยังไม่ได้ลองอ่านภาพ") + "\n" +
            "เริ่มพูดล่าสุด: " + time("last_speech_start") + "\n" +
            "พูดจบล่าสุด: " + time("last_speech_finish") + "\n" +
            "หมายเหตุ: สถานะพูดจบมาจากเครื่อง TTS ไม่ยืนยันว่าลำโพงมีเสียงจริง"
    }
}
