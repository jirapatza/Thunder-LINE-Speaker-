package com.thunder.linespeaker
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.util.Locale
class LineNotificationService : NotificationListenerService(), TextToSpeech.OnInitListener {
 private var tts: TextToSpeech? = null
 private var ready = false
 private var lastMessage = ""
 private var lastTime = 0L
 override fun onCreate() { super.onCreate(); tts = TextToSpeech(this, this) }
 override fun onInit(status: Int) {
  if (status == TextToSpeech.SUCCESS) {
   val result = tts?.setLanguage(Locale("th", "TH"))
   ready = result != null && result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
  }
 }
 override fun onNotificationPosted(sbn: StatusBarNotification) {
  if (sbn.packageName != "jp.naver.line.android") return
  val e = sbn.notification.extras
  val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
  val body = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
  val combined = "$title $body"
  if (body.isBlank() || !combined.contains("Thunder", ignoreCase = true)) return
  val pattern = Regex("(?:ยอด(?:เงิน)?|จำนวนเงิน|amount|รับเงิน|โอนเงิน)\\s*[:：]?\\s*(?:฿|THB)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
  val amount = pattern.find(combined)?.groupValues?.get(1) ?: return
  val value = amount.replace(",", "").toDoubleOrNull() ?: return
  if (value <= 0 || value > 100000000 || !ready) return
  val now = System.currentTimeMillis()
  if (combined == lastMessage && now - lastTime < 120000) return
  lastMessage = combined; lastTime = now
  tts?.speak("แจ้งเตือนธันเดอร์ ยอดเงิน ${amount} บาท", TextToSpeech.QUEUE_ADD, null, "thunder-$now")
 }
 override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
}
