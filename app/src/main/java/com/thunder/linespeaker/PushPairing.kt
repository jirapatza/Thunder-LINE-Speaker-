package com.thunder.linespeaker

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Device enrollment uses one HTTPS request and a high-entropy one-time pairing code. */
object PushPairing {
    private const val PREFS = "push_pairing"
    private const val SECRET_KEY = "pair_code"
    private const val ENDPOINT =
        "https://line-oa-slip-speaker.jirapatcip2557.workers.dev/register-device"

    fun pair(context: Context, secret: String, done: (Boolean, String) -> Unit) {
        if (secret.length < 24) {
            done(false, "รหัสจับคู่สั้นเกินไป")
            return
        }
        if (!FirebaseBootstrap.initialize(context)) {
            done(false, "แอปยังไม่ได้เชื่อมกับ Firebase โปรเจกต์จริง")
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> enroll(context.applicationContext, token, secret, done) }
            .addOnFailureListener { done(false, "รับรหัสอุปกรณ์จาก Firebase ไม่สำเร็จ") }
    }

    fun refreshToken(context: Context, token: String) {
        val secret = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SECRET_KEY, null) ?: return
        enroll(context.applicationContext, token, secret) { _, _ -> }
    }

    private fun enroll(context: Context, token: String, secret: String, done: (Boolean, String) -> Unit) {
        thread(name = "thunder-fcm-register") {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(ENDPOINT).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("x-pairing-code", secret)
                val payload = org.json.JSONObject().put("token", token).toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val ok = connection.responseCode == 200
                if (ok) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(SECRET_KEY, secret).apply()
                done(ok, if (ok) "จับคู่มือถือกับระบบแจ้งเตือนสำเร็จ"
                    else "จับคู่ไม่สำเร็จ (HTTP ${connection.responseCode})")
            } catch (_: Exception) {
                done(false, "เชื่อมต่อเซิร์ฟเวอร์แจ้งเตือนไม่สำเร็จ")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
