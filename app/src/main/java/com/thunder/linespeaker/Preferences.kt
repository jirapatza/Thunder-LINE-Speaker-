package com.thunder.linespeaker

import android.content.Context

object Preferences {
    private const val FILE = "speaker_settings"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun customers(context: Context): Boolean = prefs(context).getBoolean("customers", true)
    fun payments(context: Context): Boolean = prefs(context).getBoolean("payments", true)
    fun screenSlipOcr(context: Context): Boolean = prefs(context).getBoolean("screen_slip_ocr", false)

    fun setCustomers(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("customers", enabled).apply()
    fun setPayments(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("payments", enabled).apply()
    fun setScreenSlipOcr(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("screen_slip_ocr", enabled).apply()
    internal fun speechEnabled(context: Context, type: SpeechType): Boolean = when (type) {
        SpeechType.CUSTOMER -> customers(context)
        SpeechType.PICTURE -> payments(context)
        SpeechType.VERIFIED -> prefs(context).getBoolean("verified_speech_enabled", true)
    }
    internal fun speechText(context: Context, type: SpeechType): String =
        prefs(context).getString("speech_text_" + type.name, type.defaultText) ?: type.defaultText

    internal fun saveSpeech(context: Context, type: SpeechType, enabled: Boolean, text: String): Boolean {
        if (!SpeechSettings.validate(type, text)) return false
        val key = when (type) {
            SpeechType.CUSTOMER -> "customers"
            SpeechType.PICTURE -> "payments"
            SpeechType.VERIFIED -> "verified_speech_enabled"
        }
        return prefs(context).edit().putBoolean(key, enabled)
            .putString("speech_text_" + type.name, text.trim()).commit()
    }

    internal fun speech(context: Context, type: SpeechType, amount: java.math.BigDecimal? = null): String? =
        SpeechSettings.render(type, speechEnabled(context, type), speechText(context, type), amount)

}
