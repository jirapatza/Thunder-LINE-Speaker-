package com.thunder.linespeaker

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class LineNotificationService : NotificationListenerService() {
    private val deduper = LineNoticeDeduper()

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
        // Prefer the newest message's sender over a group/conversation title.
        val latest = messages.lastOrNull()
        val messageSender = latest?.senderPerson?.name?.toString()?.trim().orEmpty()
        val effectiveSender = messageSender.ifBlank { sender }
        val messageText = latest?.text?.toString().orEmpty().trim()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.lastOrNull()?.toString().orEmpty().trim()
        val body = messageText.ifBlank { directText }.ifBlank { textLines }
        // Do not label a new text message as a picture because an earlier
        // item in the same MessagingStyle notification contained a photo.
        val hasPicture = latest?.dataMimeType?.startsWith("image/") == true ||
            (messages.isEmpty() && (
                extras.containsKey(Notification.EXTRA_PICTURE) ||
                extras.containsKey(Notification.EXTRA_PICTURE_ICON)
            ))
        if (effectiveSender.isBlank() && body.isBlank() && !hasPicture) return

        val slipVoice = Preferences.payments(this)
        val alert = LineAnnouncementPolicy.choose(
            sender = effectiveSender,
            message = body,
            hasPicture = hasPicture,
            customerVoice = Preferences.customers(this),
            slipVoice = slipVoice
        )
        val wantsOcr = slipVoice && hasPicture && Preferences.screenOcr(this)
        if (alert == null && !wantsOcr) return

        // Notification callbacks can fire many times for one LINE message.
        // A MessagingStyle timestamp separates two identical new messages.
        val fingerprint = listOf(
            effectiveSender,
            body,
            latest?.timestamp?.toString().orEmpty(),
            hasPicture.toString(),
            alert?.kind.orEmpty()
        ).joinToString("|")
        if (!deduper.accept(sbn.key, fingerprint, SystemClock.elapsedRealtime())) return

        if (alert != null) {
            SpeechEngine.speak(
                this, alert.phrase,
                "line:" + sbn.key + ":" + fingerprint, 30_000L
            )
        }

        // Optional OCR only when LINE actually exposed a picture payload.
        // Many LINE notifications contain just "ส่งรูปภาพ" and have no bitmap.
        if (wantsOcr) {
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
