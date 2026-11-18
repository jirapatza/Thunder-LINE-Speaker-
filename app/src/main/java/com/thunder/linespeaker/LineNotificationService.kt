package com.thunder.linespeaker

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class LineNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Standard LINE package. Some cloned or work-profile installs use different IDs.
        if (sbn.packageName != "jp.naver.line.android") return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (sender.isBlank() && body.isBlank()) return

        val combined = "$sender $body"
        val thunderMessage = sender.contains("Thunder", ignoreCase = true)

        if (thunderMessage) {
            if (!Preferences.payments(this)) return
            val amount = AmountParser.findAmount(body)
            if (amount != null) {
                SpeechEngine.speak(this, "รับเงินเข้า $amount บาทแล้วเด้อ", "thunder:$sender:$body")
            } else {
                scanNotificationPicture(notification, "thunder:${sbn.key}")
            }
            return
        }

        // Customer voice: do not read actual lottery numbers or private chat contents aloud.
        if (Preferences.customers(this) && body.isNotBlank()) {
            val safeName = sender.take(40).ifBlank { "ลูกค้า" }
            val lotName = listOf(
                "ฮานอย VIP", "ฮานอยพิเศษ", "ฮานอยสามัคคี",
                "ฮานอย", "ลาวพัฒนา", "ลาวประชาชน", "ลาวอาเซียน",
                "รัฐบาล", "หุ้น"
            ).firstOrNull { body.contains(it, ignoreCase = true) }
            val phrase = if ((body.contains("หวย") || body.contains("สั่ง")) && lotName != null) {
                "$safeName ซื้อหวย$lotName"
            } else {
                "$safeName ทัก LINE มา"
            }
            SpeechEngine.speak(this, phrase, "customer:$sender:$body", 30_000L)
        }
        // For actual image text, fall back to an available picture payload (if any).
        if (Preferences.payments(this) && Preferences.screenOcr(this)) {
            scanNotificationPicture(notification, "picture:${sbn.key}")
        }
    }

    private fun scanNotificationPicture(notification: Notification, key: String) {
        val extras = notification.extras
        @Suppress("DEPRECATION")
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            extras.getParcelable(Notification.EXTRA_PICTURE)
        }
        val drawable = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE_ICON, android.graphics.drawable.Icon::class.java)
                ?.loadDrawable(this)
        } else null
        val image = bitmap ?: (drawable as? BitmapDrawable)?.bitmap ?: return
        ImageReader.fromBitmap(image) { amount ->
            if (amount != null && Preferences.payments(this)) {
                // OCR reads a picture, not bank confirmation. Never claim verification from image alone.
                SpeechEngine.speak(this, "พบยอดเงิน $amount บาทจากรูปภาพ", "$key:$amount")
            }
        }
    }
}
