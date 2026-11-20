package com.thunder.linespeaker

import android.content.Context
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Stores only hashed conversation identities and resets at Bangkok midnight. */
internal object CustomerNoticeStore {
    @Synchronized
    fun accept(context: Context, conversation: String, sender: String, nowMs: Long): Boolean {
        val identity = listOf(conversation.trim(), sender.trim()).joinToString("\u0000")
        // A missing identity must not suppress all anonymous conversations.
        if (conversation.isBlank() && sender.isBlank()) return true
        val key = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("Asia/Bangkok")
        val day = formatter.format(Date(nowMs))
        val prefs = context.applicationContext.getSharedPreferences("customer_notice_day", Context.MODE_PRIVATE)
        val sameDay = prefs.getString("date", null) == day
        if (sameDay && prefs.getBoolean(key, false)) return false
        val edit = prefs.edit()
        if (!sameDay) edit.clear().putString("date", day)
        edit.putBoolean(key, true).apply()
        return true
    }
}
