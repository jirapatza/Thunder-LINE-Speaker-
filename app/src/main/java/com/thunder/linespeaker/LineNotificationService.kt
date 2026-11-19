package com.thunder.linespeaker

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class LineNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Listen to the installed LINE app only. Leave the LINE OA webhook alone.
        if (sbn.packageName != "jp.naver.line.android") return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val directText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        val messages = try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(it)
            }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val messageText = messages.lastOrNull()?.text?.toString().orEmpty().trim()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.lastOrNull()?.toString().orEmpty().trim()
        val body = messageText.ifBlank { directText }.ifBlank { textLines }
        val hasPicture = messages.any { it.dataMimeType?.startsWith("image/") == true } ||
            extras.containsKey(Notification.EXTRA_PICTURE) ||
            extras.containsKey(Notification.EXTRA_PICTURE_ICON)
        if (sender.isBlank() && body.isBlank() && !hasPicture) return

        val slipVoice = Preferences.payments(this)
        val alert = LineAnnouncementPolicy.choose(
            sender = sender,
            message = body,
            hasPicture = hasPicture,
            customerVoice = Preferences.customers(this),
            slipVoice = slipVoice
        )
        if (alert != null) {
            SpeechEngine.speak(
                this, alert.phrase,
                "line:" + sbn.key + ":" + alert.kind + ":" + body, 30_000L
            )
        }

        // Optional OCR only when LINE actually exposed a picture payload.
        // Many LINE notifications contain just "ส่งรูปภาพ" and have no bitmap.
        if (slipVoice && hasPicture && Preferences.screenOcr(this)) {
            scanNotificationPicture(notification, "picture:" + sbn.key)
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
        val image = bitmap ?: (drawable as? BitmapDrawable)?.bitmap
        if (image == null) {
            // Some MessagingStyle apps attach a readable image Uri instead of a BigPicture.
            // LINE is not required to expose one; unreadable Uris are ignored safely.
            if (Build.VERSION.SDK_INT >= 30) {
                val parcels = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (parcels != null) {
                    val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcels)
                    for (message in messages.takeLast(2)) {
                        val uri = message.dataUri
                        if (uri != null && message.dataMimeType?.startsWith("image/") == true) {
                            ImageReader.fromUri(this, uri) { amount ->
                                if (amount != null && Preferences.payments(this)) {
                                    SpeechEngine.speak(this, "พบยอดเงิน $amount บาทจากรูปภาพ", "$key:$amount")
                                }
                            }
                        }
                    }
                }
            }
            return
        }
        ImageReader.fromBitmap(image) { amount ->
            if (amount != null && Preferences.payments(this)) {
                // OCR reads a picture, not bank confirmation. Never claim verification from image alone.
                SpeechEngine.speak(this, "พบยอดเงิน $amount บาทจากรูปภาพ", "$key:$amount")
            }
        }
    }
}
