package com.thunder.linespeaker

import android.app.Notification
import android.content.ComponentName
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
        // Android sends callbacks for many apps; do not inspect their extras.
        if (!LineSourcePolicy.accepts(sbn.packageName)) return
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

        for (item in incoming) {
            if (item.sender.isBlank() && item.body.isBlank() && !item.hasPicture) continue
            val alert = LineAnnouncementPolicy.choose(
                sender = item.sender,
                message = item.body,
                hasPicture = item.hasPicture,
                customerVoice = customerVoice,
                slipVoice = slipVoice
            )
            if (alert == null) continue

            // Fingerprint the message, not the entire bundled notification.
            // Never retain the original chat text in deduplication keys.
            val fingerprintSource = listOf(
                item.sender, item.body, item.timestampMs.toString(),
                item.hasPicture.toString(), alert.kind
            ).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(fingerprintSource.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (!deduper.accept(sbn.key, digest, SystemClock.elapsedRealtime())) continue

            if (alert.kind == "customer" && !CustomerNoticeStore.accept(
                    this, conversationTitle, item.sender, nowMs
                )) continue

            VoiceHealth.receivedLineNotice(this)
            val speechKey = "line:" + sbn.key + ":" + digest
            if (alert.kind == "picture" || alert.kind == "slip") {
                val attachedUri = messages.lastOrNull { message ->
                    message.timestamp == item.timestampMs &&
                        message.dataMimeType?.startsWith("image/") == true
                }?.dataUri
                val imageAvailable = try {
                    SlipImageReader.fromNotification(this, notification, attachedUri, allowUnlabeled = alert.kind == "slip") { amount ->
                        if (amount == null) {
                            VoiceHealth.slipOcrStatus(this, "Android แนบภาพมา แต่ OCR ไม่พบยอดเงินที่อ่านได้")
                            SpeechEngine.speak(this, alert.phrase, speechKey, 30_000L)
                        } else {
                            VoiceHealth.slipOcrStatus(this, "OCR อ่านยอดจากภาพ LINE OA ที่แนบมาได้")
                            SpeechEngine.speak(
                                this, "พบยอดในรูปสลิป $amount บาทแล้วเด้อ", speechKey, 30_000L
                            )
                        }
                    }
                } catch (_: Exception) {
                    false
                }
                if (!imageAvailable) {
                    VoiceHealth.slipOcrStatus(this, "LINE OA แจ้งรูปภาพ แต่ Android ไม่แนบไฟล์ภาพให้แอปอ่าน")
                    SpeechEngine.speak(this, alert.phrase, speechKey, 30_000L)
                }
            } else {
                SpeechEngine.speak(this, alert.phrase, speechKey, 30_000L)
            }
        }
    }

}
