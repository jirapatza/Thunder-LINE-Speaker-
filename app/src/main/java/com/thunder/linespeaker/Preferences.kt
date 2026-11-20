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
}
