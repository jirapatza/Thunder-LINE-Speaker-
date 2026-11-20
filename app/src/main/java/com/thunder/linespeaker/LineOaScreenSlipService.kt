package com.thunder.linespeaker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, user-authorized visible-screen OCR, restricted to LINE OA.
 * No screen recording, no background reading of other apps or chat history.
 */
class LineOaScreenSlipService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var captureScheduled = false
    private var lastCaptureMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !Preferences.screenSlipOcr(this) || !Preferences.payments(this)) return
        if (!LineSourcePolicy.accepts(event?.packageName?.toString())) return
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val now = SystemClock.elapsedRealtime()
        if (busy || captureScheduled || now - lastCaptureMs < 8_000L) return
        captureScheduled = true
        handler.postDelayed({
            captureScheduled = false
            // Verify foreground owner immediately before capturing any pixels.
            if (LineSourcePolicy.accepts(rootInActiveWindow?.packageName?.toString()) &&
                Preferences.screenSlipOcr(this) && Preferences.payments(this)) {
                captureVisibleLineOa()
            }
        }, 800L)
    }

    private fun captureVisibleLineOa() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || busy) return
        busy = true
        lastCaptureMs = SystemClock.elapsedRealtime()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hardwareBuffer = result.hardwareBuffer
                        val image: Bitmap? = try {
                            Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                            null
                        } finally {
                            hardwareBuffer.close()
                        }
                        if (image == null) {
                            busy = false
                            VoiceHealth.slipOcrStatus(this@LineOaScreenSlipService, "จับภาพ LINE OA ไม่สำเร็จ")
                            return
                        }
                        SlipImageReader.fromBitmap(image, allowUnlabeled = false) { amount ->
                            image.recycle()
                            busy = false
                            if (amount != null && Preferences.screenSlipOcr(this@LineOaScreenSlipService)
                                && Preferences.payments(this@LineOaScreenSlipService)) {
                                VoiceHealth.slipOcrStatus(this@LineOaScreenSlipService, "อ่านยอดจากภาพที่เปิดใน LINE OA ได้")
                                SpeechEngine.speak(
                                    this@LineOaScreenSlipService,
                                    "พบยอดในภาพ LINE OA $amount บาทแล้วเด้อ",
                                    "visible-oa-amount:$amount",
                                    120_000L
                                )
                            } else {
                                VoiceHealth.slipOcrStatus(this@LineOaScreenSlipService, "ภาพที่เปิดอยู่ไม่พบยอดเงินที่อ่านได้")
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        busy = false
                        VoiceHealth.slipOcrStatus(this@LineOaScreenSlipService, "ระบบไม่อนุญาตจับภาพ LINE OA")
                    }
                }
            )
        } catch (_: Exception) {
            busy = false
            VoiceHealth.slipOcrStatus(this, "จับภาพ LINE OA ไม่สำเร็จ")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
