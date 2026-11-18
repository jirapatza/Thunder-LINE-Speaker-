package com.thunder.linespeaker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** On-device OCR: no image or recognized text is uploaded by this app. */
object ImageReader {
    private val detector by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun fromBitmap(bitmap: Bitmap, completed: (String?) -> Unit) {
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { completed(AmountParser.findAmount(it.text)) }
            .addOnFailureListener { completed(null) }
    }

    fun fromUri(context: Context, uri: Uri, completed: (String?) -> Unit) {
        try {
            val input = InputImage.fromFilePath(context, uri)
            detector.process(input)
                .addOnSuccessListener { completed(AmountParser.findAmount(it.text)) }
                .addOnFailureListener { completed(null) }
        } catch (_: Exception) {
            completed(null)
        }
    }
}
