package com.thunder.linespeaker

/**
 * Strict allowlist. Android grants Notification Access for the device, but
 * this app processes only notifications from the official LINE OA Android app.
 * Personal LINE, banks, SMS, and all other apps are excluded.
 */
internal object LineSourcePolicy {
    const val OA_PACKAGE = "com.linecorp.lineoa"

    fun accepts(packageName: String?): Boolean = packageName == OA_PACKAGE
}
