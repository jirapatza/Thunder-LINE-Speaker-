package com.thunder.linespeaker

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONObject

/** Optional pairing with the existing Cloudflare Worker; secrets never in APK. */
object PushPairing {
    private const val PREFS = "push_pairing"
    private const val PAIR_KEY = "pair_code"
    private const val LAST_ENROLLED = "last_enrolled_at_ms"
    private const val INTERVAL = 24L * 60 * 60 * 1000
    private const val BASE = "https://line-oa-slip-speaker.jirapatcip2557.workers.dev"

    fun pair(context: Context, code: String, done: (Boolean, String) -> Unit) {
        if (code.length < 24) {
            done(false, "รหัสจับคู่ต้องมีอย่างน้อย 24 ตัวอักษร")
            return
        }
        if (!FirebaseBootstrap.initialize(context)) {
            done(false, "ยังไม่ได้ตั้งค่า Firebase ให้ APK รุ่นนี้")
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> enroll(context.applicationContext, token, code, done) }
            .addOnFailureListener { done(false, "รับรหัสอุปกรณ์จาก Firebase ไม่สำเร็จ") }
    }

    fun refreshToken(context: Context, token: String) {
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val code=p.getString(PAIR_KEY,null) ?: return
        enroll(context.applicationContext,token,code){_,_->}
    }

    fun refreshWhenAppOpens(context: Context) {
        if (!FirebaseBootstrap.initialize(context)) return
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val code=p.getString(PAIR_KEY,null) ?: return
        val last=p.getLong(LAST_ENROLLED,0L)
        if (System.currentTimeMillis()-last in 0 until INTERVAL) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener{token->enroll(context.applicationContext,token,code){_,_->}}
    }

    fun status(done: (String) -> Unit) {
        thread(name="line-oa-relay-health") {
            var conn: HttpURLConnection?=null
            val msg=try {
                conn=URL(BASE + "/health").openConnection() as HttpURLConnection
                conn.connectTimeout=10_000;conn.readTimeout=10_000
                if(conn.responseCode!=200) {
                    "เซิร์ฟเวอร์ตอบ HTTP " + conn.responseCode
                }else{
                    val data=JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
                    "โหมด Worker: " + data.optString("processingMode", "ไม่ทราบ") + "\n" +
                        "LINE OA: " + (if(data.optBoolean("lineConfigured")) "ตั้งค่าแล้ว" else "ยังไม่มีคีย์") + "\n" +
                        "Firebase: " + (if(data.optBoolean("fcmConfigured")) "ตั้งค่าแล้ว" else "ยังไม่มีคีย์") + "\n" +
                        "มือถือที่จับคู่: " + data.optInt("pairedDevices",0) + "\n" +
                        "พร้อมทดสอบ: " + (data.optString("status")=="ready_for_live_test")
                }
            } catch(_:Exception) {
                "ตรวจสถานะ Cloudflare ไม่ได้ (ตรวจอินเทอร์เน็ตและ URL)"
            } finally {conn?.disconnect()}
            done(msg)
        }
    }

    private fun enroll(context:Context, token:String, code:String, done:(Boolean,String)->Unit){
        thread(name="line-oa-relay-pairing"){
            var conn:HttpURLConnection?=null
            try {
                conn=URL(BASE + "/register-device").openConnection() as HttpURLConnection
                conn.requestMethod="POST";conn.doOutput=true
                conn.connectTimeout=10_000;conn.readTimeout=10_000
                conn.setRequestProperty("content-type","application/json; charset=utf-8")
                conn.setRequestProperty("x-pairing-code",code)
                conn.outputStream.use{it.write(JSONObject().put("token",token).toString().toByteArray())}
                val success=conn.responseCode==200
                if(success) context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                    .putString(PAIR_KEY,code)
                    .putLong(LAST_ENROLLED,System.currentTimeMillis()).apply()
                done(success,if(success)"จับคู่มือถือกับ LINE OA Relay สำเร็จ"
                    else "จับคู่ไม่สำเร็จ HTTP " + conn.responseCode)
            } catch(_:Exception){
                done(false,"ลงทะเบียนมือถือกับ Cloudflare ไม่สำเร็จ")
            } finally{conn?.disconnect()}
        }
    }
}
