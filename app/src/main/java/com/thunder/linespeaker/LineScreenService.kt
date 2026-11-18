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
 * With explicit Accessibility permission, processes only the currently visible
 * LINE screen on-device. Does not open chats, scroll, or collect chat history.
 */
class LineScreenService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var captureScheduled = false
    private var captureBusy = false
    private var lastCapture = 0L
    private var lastFrameHash: Int? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Preferences.screenOcr(this) ||
            !Preferences.payments(this)) return
        if (event?.packageName?.toString() != "jp.naver.line.android") return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val now = SystemClock.elapsedRealtime()
        if (captureBusy || captureScheduled || now - lastCapture < 3500L) return

        captureScheduled = true
        handler.postDelayed({
            captureScheduled = false
            if (rootInActiveWindow?.packageName?.toString() == "jp.naver.line.android" &&
                Preferences.screenOcr(this) && Preferences.payments(this)) {
                readVisibleLineScreen()
            }
        }, 900L)
    }

    private fun readVisibleLineScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || captureBusy) return
        lastCapture = SystemClock.elapsedRealtime()
        captureBusy = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val bitmap: Bitmap? = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                            null
                        } finally {
                            buffer.close()
                        }
                        if (bitmap == null) {
                            captureBusy = false
                            return
                        }
                        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                        val pixels = IntArray(32 * 32)
                        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                        small.recycle()
                        val hash = pixels.contentHashCode()
                        if (hash == lastFrameHash) {
                            bitmap.recycle()
                            captureBusy = false
                            return
                        }
                        lastFrameHash = hash
                        ImageReader.fromBitmap(bitmap) { amount ->
                            bitmap.recycle()
                            captureBusy = false
                            if (amount != null && Preferences.payments(this@LineScreenService)) {
                                SpeechEngine.speak(
                                    this@LineScreenService,
                                    "พบยอดเงิน $amount บาทจากรูปภาพ",
                                    "visible-screen:$hash:$amount",
                                    120_000L
                                )
                            }
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        captureBusy = false
                    }
                })
        } catch (_: Exception) {
            captureBusy = false
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
