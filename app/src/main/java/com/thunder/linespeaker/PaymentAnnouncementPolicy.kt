package com.thunder.linespeaker

import java.math.BigDecimal

/**
 * A LINE sender display name, message text, or OCR picture is NOT proof that
 * money arrived. The "รับเงินเข้า" phrase must only be produced for the
 * authenticated Firebase relay's verified payment event.
 */
internal object PaymentAnnouncementPolicy {
    enum class Source { VERIFIED_FCM, LINE_NOTIFICATION_TEXT, IMAGE_OCR }

    fun confirmedAmount(
        source: Source,
        type: String?,
        eventId: String?,
        amountText: String?,
        eventTimeText: String?,
        nowMs: Long = System.currentTimeMillis()
    ): BigDecimal? {
        if (source != Source.VERIFIED_FCM || type != "payment_matched" ||
            eventId == null || !Regex("^[0-9]{1,32}$").matches(eventId)) return null
        // Cloudflare signs the FCM data using its configured Firebase project.
        // Never speak a delayed or legacy push without an original LINE time.
        val eventTime = eventTimeText?.toLongOrNull() ?: return null
        if (eventTime < nowMs - 10 * 60_000L ||
            eventTime > nowMs + 60_000L) return null
        val amount = amountText?.toBigDecimalOrNull() ?: return null
        if (amount <= BigDecimal.ZERO || amount > BigDecimal("100000000")) return null
        return amount
    }
}
