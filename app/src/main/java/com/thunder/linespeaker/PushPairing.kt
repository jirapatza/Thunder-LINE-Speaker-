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
    private const val LAST_ENROLLED_KEY = "last_enrolled_at_ms"
    private const val REPAIR_INTERVAL_MS = 24L * 60 * 60 * 1000
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

    /**
     * Refreshes the server registration after the app is opened. FCM tokens can
     * stay unchanged beyond the 90-day Cloudflare KV expiry, so relying only on
     * onNewToken() eventually stops notifications silently.
     */
    fun refreshWhenAppOpens(context: Context) {
        if (!FirebaseBootstrap.initialize(context)) return
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val secret = preferences.getString(SECRET_KEY, null) ?: return
        val previous = preferences.getLong(LAST_ENROLLED_KEY, 0L)
        if (System.currentTimeMillis() - previous in 0 until REPAIR_INTERVAL_MS) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                enroll(context.applicationContext, token, secret) { _, _ -> }
            }
    }

    fun checkServerStatus(done: (String) -> Unit) {
        thread(name = "thunder-server-readiness") {
            var connection: HttpURLConnection? = null
            val report = try {
                val healthUrl = ENDPOINT.removeSuffix("/register-device") + "/health"
                connection = URL(healthUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"
                if (connection.responseCode != 200) {
                    "Cloudflare ไม่ตอบสถานะปกติ (HTTP ${connection.responseCode})"
                } else {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val status = org.json.JSONObject(body)
                    val line = status.optBoolean("lineConfigured", false)
                    val thunder = status.optBoolean("thunderConfigured", false)
                    val firebase = status.optBoolean("fcmConfigured", false)
                    val devices = status.optInt("pairedDevices", 0)
                    val ready = status.optString("status") == "ready_for_live_test"
                    listOf(
                        "สถานะ Cloudflare: " + if (ready) "พร้อมเริ่มทดสอบจริง" else "ยังตั้งค่าไม่ครบ",
                        "LINE OA: " + if (line) "มีคีย์" else "ยังไม่มีคีย์",
                        "Thunder: " + if (thunder) "มีคีย์" else "ยังไม่มีคีย์",
                        "Firebase: " + if (firebase) "มีคีย์" else "ยังไม่มีคีย์",
                        "อุปกรณ์ที่จับคู่: $devices",
                        "การอ่านออกเสียงบนมือถือจริง: ยังไม่ได้ยืนยัน"
                    ).joinToString("\n")
                }
            } catch (_: Exception) {
                "ตรวจ Cloudflare ไม่สำเร็จ (ตรวจอินเทอร์เน็ตหรือ URL)"
            } finally {
                connection?.disconnect()
            }
            done(report)
        }
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
                    .putString(SECRET_KEY, secret)
                    .putLong(LAST_ENROLLED_KEY, System.currentTimeMillis())
                    .apply()
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
