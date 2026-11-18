package com.thunder.linespeaker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.math.BigDecimal
import java.math.RoundingMode

/** Speaks only trusted, bank-verified payment events from the configured Firebase project. */
class VerifiedPaymentMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushPairing.refreshToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data["type"] != "payment_matched" || !Preferences.payments(this)) return
        val eventId = data["eventId"] ?: return
        if (!Regex("^[0-9]{1,32}$").matches(eventId)) return
        val amount = data["amount"]?.toBigDecimalOrNull() ?: return
        if (amount <= BigDecimal.ZERO || amount > BigDecimal("100000000")) return

        // Remember the last 100 verified IDs across process restarts.
        val prefs = getSharedPreferences("verified_push_events", Context.MODE_PRIVATE)
        synchronized(VerifiedPaymentMessagingService::class.java) {
            val old = prefs.getString("ids", "").orEmpty()
                .split(",").filter { it.isNotBlank() }
            if (old.contains(eventId)) return
            prefs.edit().putString("ids", (old + eventId).takeLast(100).joinToString(",")).commit()
        }

        val readable = amount.setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
        showNotification(eventId, readable)
        SpeechEngine.speak(this, "รับเงินเข้า $readable บาทแล้วเด้อ", "fcm:${eventId}", 86_400_000L)
    }

    private fun showNotification(eventId: String, amount: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "thunder_verified_payments"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channelId, "ยอดเงินที่ตรวจสลิปแล้ว", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Thunder ตรวจสอบสลิป")
            .setContentText("รับเงินเข้า $amount บาท")
            .setAutoCancel(true)
            .build()
        manager.notify(eventId.hashCode(), notification)
    }
}
