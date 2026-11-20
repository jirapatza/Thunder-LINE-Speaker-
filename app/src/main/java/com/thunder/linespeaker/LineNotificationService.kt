package com.thunder.linespeaker

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

class LineNotificationService : NotificationListenerService() {
    private val deduper = LineNoticeDeduper()

    override fun onListenerConnected() {
        super.onListenerConnected()
        VoiceHealth.listenerConnected(this)
    }

    override fun onListenerDisconnected() {
        VoiceHealth.listenerDisconnected()
        // This request is explicitly permitted by the Android platform after
        // disconnection. It cannot override OEM process-killing policies.
        try {
            requestRebind(ComponentName(this, LineNotificationService::class.java))
        } catch (_: RuntimeException) {
            // The user may have revoked access or Android may refuse rebind.
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "jp.naver.line.android") return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString().orEmpty().trim()
        val directText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        val messages = try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(it)
            }.orEmpty()
        } catch (_: RuntimeException) {
            emptyList()
        }

        val nowMs = System.currentTimeMillis()
        val rawItems = if (messages.isNotEmpty()) {
            messages.map { item ->
                val who = item.senderPerson?.name?.toString()?.trim().orEmpty()
                    .ifBlank { conversationTitle }
                LineNoticeItem(
                    sender = who,
                    body = item.text?.toString().orEmpty().trim(),
                    hasPicture = item.dataMimeType?.startsWith("image/") == true,
                    timestampMs = item.timestamp
                )
            }
        } else {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.lastOrNull()?.toString().orEmpty().trim()
            val body = directText.ifBlank { lines }
            val picture = extras.containsKey(Notification.EXTRA_PICTURE) ||
                extras.containsKey(Notification.EXTRA_PICTURE_ICON)
            listOf(LineNoticeItem(conversationTitle, body, picture, 0L))
        }
        val incoming = LineNoticeBatchSelector.fresh(rawItems, nowMs)
        if (incoming.isEmpty()) return

        val slipVoice = Preferences.payments(this)
        val customerVoice = Preferences.customers(this)
        val wantsOcr = slipVoice && Preferences.screenOcr(this)

        for ((index, item) in incoming.withIndex()) {
            if (item.sender.isBlank() && item.body.isBlank() && !item.hasPicture) continue
            val alert = LineAnnouncementPolicy.choose(
                sender = item.sender,
                message = item.body,
                hasPicture = item.hasPicture,
                customerVoice = customerVoice,
                slipVoice = slipVoice
            )
            val scanPicture = wantsOcr && item.hasPicture && index == incoming.lastIndex
            if (alert == null && !scanPicture) continue

            // Fingerprint the message, not the entire bundled notification.
            // Never retain the original chat text in deduplication keys.
            val fingerprintSource = listOf(
                item.sender, item.body, item.timestampMs.toString(),
                item.hasPicture.toString(), alert?.kind.orEmpty()
            ).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(fingerprintSource.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (!deduper.accept(sbn.key, digest, SystemClock.elapsedRealtime())) continue

            VoiceHealth.receivedLineNotice(this)
            if (alert != null) {
                SpeechEngine.speak(this, alert.phrase, "line:" + sbn.key + ":" + digest, 30_000L)
            }
            if (scanPicture) scanNotificationPicture(notification, "picture:" + sbn.key + ":" + digest)
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
