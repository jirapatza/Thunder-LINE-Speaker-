package com.thunder.linespeaker

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR. No uploaded images, network requests or bank validation.
 * A LINE OA notification may not expose the photo bytes at all.
 */
internal object SlipImageReader {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun fromBitmap(bitmap: Bitmap, callback: (String?) -> Unit) {
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { callback(SlipAmountParser.find(it.text)) }
                .addOnFailureListener { callback(null) }
        } catch (_: Exception) {
            callback(null)
        }
    }

    fun fromUri(context: Context, uri: Uri, callback: (String?) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { callback(SlipAmountParser.find(it.text)) }
                .addOnFailureListener { callback(null) }
        } catch (_: Exception) {
            callback(null)
        }
    }

    /**
     * Tries real image payloads only. Returns false if there is no exposed
     * picture or URI. An image notification title is NOT a picture payload.
     */
    fun fromNotification(
        context: Context,
        notification: Notification,
        imageUri: Uri?,
        callback: (String?) -> Unit
    ): Boolean {
        if (imageUri != null) {
            fromUri(context, imageUri, callback)
            return true
        }
        val extras = notification.extras
        @Suppress("DEPRECATION")
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            extras.getParcelable(Notification.EXTRA_PICTURE)
        }
        if (bitmap != null) {
            fromBitmap(bitmap, callback)
            return true
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val icon = extras.getParcelable(
                Notification.EXTRA_PICTURE_ICON,
                android.graphics.drawable.Icon::class.java
            )
            val drawable = try { icon?.loadDrawable(context) } catch (_: Exception) { null }
            val iconBitmap = (drawable as? BitmapDrawable)?.bitmap
            if (iconBitmap != null) {
                fromBitmap(iconBitmap, callback)
                return true
            }
        }
        return false
    }
}
