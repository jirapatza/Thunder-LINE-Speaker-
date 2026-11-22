package com.thunder.linespeaker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Downloads an expiring, signed LINE OA image from our own Worker and OCRs it
 * on the handset. Never calls Thunder and never claims bank verification.
 */
class IncomingSlipWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {

    override fun doWork(): Result {
        if (!Preferences.payments(applicationContext)) return Result.success()
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val at = inputData.getLong(KEY_EVENT_TIME, 0L)
        val link = inputData.getString(KEY_IMAGE_URL) ?: return Result.failure()
        val now = System.currentTimeMillis()
        if (at < now - 600_000L || at > now + 60_000L ||
            !allowedImageUrl(link, id, at)) return Result.success()

        val file: File
        try {
            file = downloadImage(link)
        } catch (_: Exception) {
            VoiceHealth.slipOcrStatus(applicationContext, "ดาวน์โหลดรูป LINE OA ไม่สำเร็จ")
            return if (runAttemptCount < 2 && at > System.currentTimeMillis() - 120_000L)
                Result.retry() else Result.failure()
        }

        var amount: String? = null
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth < 100 || opts.outHeight < 100 ||
                opts.outWidth > 20_000 || opts.outHeight > 20_000)
                return Result.failure()
            var sample = 1
            while (opts.outWidth / sample > 2400 || opts.outHeight / sample > 2400)
                sample *= 2
            val image = BitmapFactory.decodeFile(file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return Result.failure()
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)),
                        25, TimeUnit.SECONDS).text
                    amount = SlipAmountParser.find(text, allowUnlabeledSingleDecimal = true)
                } finally {
                    recognizer.close()
                }
            } finally {
                image.recycle()
            }
        } catch (_: Exception) {
            VoiceHealth.slipOcrStatus(applicationContext, "OCR รูปจาก LINE OA ไม่สำเร็จ")
            return Result.failure()
        } finally {
            file.delete()
        }

        // WorkManager uses unique jobs per LINE message, with a local second
        // dedupe guard for process restarts and unexpected redelivery.
        val cache = applicationContext.getSharedPreferences("image_relay_spoken", Context.MODE_PRIVATE)
        synchronized(IncomingSlipWorker::class.java) {
            val recent = cache.getString("ids", "").orEmpty().split(",")
                .filter { it.isNotBlank() }
            if (recent.contains(id)) return Result.success()
            cache.edit().putString("ids", (recent + id).takeLast(150).joinToString(",")).apply()
        }
        VoiceHealth.slipOcrStatus(applicationContext,
            if (amount == null) "ได้รูปจาก LINE OA แต่ OCR ไม่พบยอดที่เชื่อถือได้"
            else "อ่านภาพสำเร็จ แต่ยอดจาก OCR ไม่ใช่ผลตรวจยืนยัน")
        Preferences.speech(applicationContext, SpeechType.PICTURE)?.let { phrase ->
            SpeechEngine.speak(applicationContext, phrase, "image-relay:" + id, 600_000L)
        }
        return Result.success()
    }

    private fun downloadImage(value: String): File {
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        val output = File.createTempFile("line_oa_slip_", ".img", applicationContext.cacheDir)
        try {
            if (connection.responseCode != 200) throw IllegalStateException("download_failed")
            val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (mime !in listOf("image/jpeg", "image/png", "image/webp"))
                throw IllegalStateException("invalid_image_type")
            if (connection.contentLengthLong > MAX_BYTES)
                throw IllegalStateException("oversize_image")
            connection.inputStream.use { input ->
                FileOutputStream(output).use { sink ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > MAX_BYTES) throw IllegalStateException("oversize_image")
                        sink.write(buffer, 0, read)
                    }
                    if (size < 1) throw IllegalStateException("empty_image")
                }
            }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val KEY_ID = "event_id"
        const val KEY_EVENT_TIME = "event_time"
        const val KEY_IMAGE_URL = "image_url"
        private const val MAX_BYTES = 4_194_304L
        private const val HOST = "line-oa-slip-speaker.jirapatcip2557.workers.dev"

        fun allowedImageUrl(value: String, eventId: String, eventTime: Long): Boolean {
            if (!eventId.matches(Regex("^[0-9]{1,32}$"))) return false
            return try {
                val uri = URI(value)
                if (uri.scheme != "https" || uri.host != HOST || uri.port != -1 ||
                    uri.path != "/slip-image" || uri.userInfo != null ||
                    uri.fragment != null) return false
                val query = uri.rawQuery?.split("&")?.associate { part ->
                    val pieces = part.split("=", limit = 2)
                    pieces[0] to pieces.getOrElse(1) { "" }
                } ?: return false
                if (query.size != 5) return false
                val id = query["id"] ?: return false
                val at = query["at"]?.toLongOrNull() ?: return false
                val expiry = query["exp"]?.toLongOrNull() ?: return false
                val device = query["device"] ?: return false
                val signature = query["sig"] ?: return false
                id == eventId && at == eventTime &&
                    expiry >= System.currentTimeMillis() - 10_000L &&
                    expiry <= System.currentTimeMillis() + 120_000L &&
                    device.matches(Regex("^[a-f0-9]{64}$")) &&
                    signature.matches(Regex("^[a-f0-9]{64}$"))
            } catch (_: Exception) { false }
        }
    }
}
