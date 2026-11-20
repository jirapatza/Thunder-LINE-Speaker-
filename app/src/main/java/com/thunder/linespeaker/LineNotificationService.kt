package com.thunder.linespeaker

import android.app.Notification
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.os.SystemClock
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

class LineNotificationService : NotificationListenerService() {
    private val deduper = LineNoticeDeduper()
    private var lastAutoOpenMs: Long? = null

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

        for ((index, item) in incoming.withIndex()) {
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
                    tryOpenImageChat(notification, index == incoming.lastIndex)
                }
            } else {
                SpeechEngine.speak(this, alert.phrase, speechKey, 30_000L)
            }
        }
    }

    private fun tryOpenImageChat(notification: Notification, latest: Boolean) {
        if (!Preferences.autoOpenImage(this)) {
            VoiceHealth.slipOcrStatus(this, "ไม่มีภาพแนบ และยังปิดตัวเลือกทดลองเปิดแชต")
            return
        }
        if (!Preferences.screenSlipOcr(this)) {
            VoiceHealth.slipOcrStatus(this, "ไม่มีภาพแนบ กรุณาเปิดตัวเลือกอ่านภาพหน้าจอ LINE OA")
            return
        }
        val power = getSystemService(PowerManager::class.java) ?: return
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return
        val manager = getSystemService(AccessibilityManager::class.java) ?: return
        val component = ComponentName(this, LineOaScreenSlipService::class.java)
        val accessibilityEnabled = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { info ->
            val service = info.resolveInfo?.serviceInfo
            service != null && ComponentName(service.packageName, service.name) == component
        }
        if (!accessibilityEnabled) {
            VoiceHealth.slipOcrStatus(this, "กรุณาอนุญาต Accessibility สำหรับอ่านภาพ LINE OA")
            return
        }
        if (!power.isInteractive || keyguard.isKeyguardLocked) {
            VoiceHealth.slipOcrStatus(this, "เครื่องล็อกหรือปิดหน้าจอ เปิดแชตอัตโนมัติไม่ได้")
            return
        }
        val action = notification.contentIntent ?: run {
            VoiceHealth.slipOcrStatus(this, "แจ้งเตือน LINE OA ไม่มีปุ่มเปิดแชต กรุณาเปิดแชตเอง")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!ImageChatOpenPolicy.canOpen(
                Preferences.autoOpenImage(this), Preferences.screenSlipOcr(this), accessibilityEnabled,
                power.isInteractive, keyguard.isKeyguardLocked, latest, action.creatorPackage,
                now, lastAutoOpenMs
            )) return
        lastAutoOpenMs = now
        try {
            // Android may reject background activity starts; sending an intent
            // does not establish that the expected conversation was opened.
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                action.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                action.send()
            }
            VoiceHealth.slipOcrStatus(this, "ส่งคำขอเปิดแชตแล้ว ตรวจหน้าจอ LINE OA ว่าเปิดสำเร็จ")
        } catch (_: PendingIntent.CanceledException) {
            VoiceHealth.slipOcrStatus(this, "เปิดแชตจากแจ้งเตือนไม่ได้ กรุณาเปิดแชตเอง")
        } catch (_: SecurityException) {
            VoiceHealth.slipOcrStatus(this, "Android ไม่อนุญาตให้เปิดแชต กรุณาเปิดแชตเอง")
        }
    }

}
