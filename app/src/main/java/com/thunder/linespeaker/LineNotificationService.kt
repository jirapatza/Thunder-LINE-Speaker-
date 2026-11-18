package com.thunder.linespeaker

import android.app.Notification
import android.graphics.Bitmap
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class LineNotificationService : NotificationListenerService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastKey = ""
    private var lastTime = 0L

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("th", "TH"))
            ready = result != null && result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "jp.naver.line.android") return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $body"

        // Text messages must explicitly mention Thunder.
        if (combined.contains("Thunder", ignoreCase = true)) {
            val amount = parseAmount(combined)
            if (amount != null) {
                speakAmount(amount, "text:${sbn.key}:${amount}")
                return
            }
        }

        // Android may provide a picture in a BigPicture notification.
        // Most LINE image notifications do NOT include the actual slip bitmap.
        @Suppress("DEPRECATION")
        val picture: Bitmap? = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            extras.getParcelable(Notification.EXTRA_PICTURE)
        }
        if (picture != null) {
            recognizer.process(InputImage.fromBitmap(picture, 0))
                .addOnSuccessListener { result ->
                    // Only speak when an explicit monetary label/currency is recognized.
                    val amount = parseAmount(result.text)
                    if (amount != null) speakAmount(amount, "image:${sbn.key}:${amount}")
                }
        }
    }

    private fun parseAmount(text: String): String? {
        val pattern = Regex(
            "(?:ยอด(?:เงิน)?|จำนวนเงิน|รับเงิน|โอนเงิน|amount|total|THB|฿)\\s*[:：]?\\s*(?:฿|THB)?\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]{1,8}(?:\\.[0-9]{1,2})?)",
            RegexOption.IGNORE_CASE
        )
        val amount = pattern.find(text)?.groupValues?.get(1) ?: return null
        val value = amount.replace(",", "").toDoubleOrNull() ?: return null
        if (value <= 0.0 || value > 100000000.0) return null
        return amount
    }

    @Synchronized
    private fun speakAmount(amount: String, key: String) {
        if (!ready) return
        val now = System.currentTimeMillis()
        if (lastKey == key && now - lastTime < 120000) return
        lastKey = key
        lastTime = now
        tts?.speak("รับเงินเข้า $amount บาทแล้วเด้อ", TextToSpeech.QUEUE_ADD, null, "payment-$now")
    }

    override fun onDestroy() {
        recognizer.close()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
