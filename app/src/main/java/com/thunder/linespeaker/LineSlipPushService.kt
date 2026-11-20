package com.thunder.linespeaker

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * FCM data messages announce availability of a LINE OA image, not a deposit.
 * The signed image link is consumed in a background WorkManager job.
 */
class LineSlipPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushPairing.refreshToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (!Preferences.payments(this) || data["type"] != "slip_image_ready") return
        val id = data["eventId"] ?: return
        if (!id.matches(Regex("^[0-9]{1,32}$"))) return
        val timestamp = data["eventTime"]?.toLongOrNull() ?: return
        val url = data["imageUrl"] ?: return
        val now = System.currentTimeMillis()
        if (timestamp < now - 600_000L || timestamp > now + 60_000L) return
        if (!IncomingSlipWorker.allowedImageUrl(url, id, timestamp)) return

        val input = Data.Builder()
            .putString(IncomingSlipWorker.KEY_ID, id)
            .putLong(IncomingSlipWorker.KEY_EVENT_TIME, timestamp)
            .putString(IncomingSlipWorker.KEY_IMAGE_URL, url)
            .build()
        val request = OneTimeWorkRequest.Builder(IncomingSlipWorker::class.java)
            .setInputData(input)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("line-oa-image:" + id, ExistingWorkPolicy.KEEP, request)
    }
}
