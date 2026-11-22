package com.thunder.linespeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class PaymentAnnouncementPolicyTest {
    private val now = 1_800_000_000_000L
    private val source = PaymentAnnouncementPolicy.Source.VERIFIED_FCM

    private fun amount(
        origin: PaymentAnnouncementPolicy.Source = source,
        type: String? = "payment_matched",
        id: String? = "123456789",
        value: String? = "180.00",
        eventTime: String? = now.toString()
    ) = PaymentAnnouncementPolicy.confirmedAmount(origin, type, id, value, eventTime, now)

    @Test fun confirmedFcmPaymentIsEligible() {
        assertEquals(BigDecimal("180.00"), amount())
    }

    @Test fun untrustedLineTextAndOcrCanNeverClaimReceivedMoney() {
        for (origin in listOf(
            PaymentAnnouncementPolicy.Source.LINE_NOTIFICATION_TEXT,
            PaymentAnnouncementPolicy.Source.IMAGE_OCR
        )) {
            assertNull(amount(origin = origin))
        }
    }

    @Test fun malformedOrUnverifiedFirebaseEventsAreRejected() {
        for (type in listOf("slip_unmatched", "duplicate_slip", "payment_pending", null)) {
            assertNull(amount(type = type))
        }
        for (id in listOf("", "invalid-id", "1".repeat(33), null)) {
            assertNull(amount(id = id))
        }
        for (value in listOf("0", "-1", "100000000.01", "foo", "", "1e2", "12.345", null)) {
            assertNull(amount(value = value))
        }
    }

    @Test fun missingMalformedFutureAndExpiredPaymentTimestampsAreRejected() {
        for (eventTime in listOf(
            null, "", "not-a-number", (now - 10 * 60_000L - 1L).toString(),
            (now + 60_001L).toString(), Long.MAX_VALUE.toString()
        )) {
            assertNull(amount(eventTime = eventTime))
        }
        assertEquals(BigDecimal("180.00"), amount(eventTime = (now - 10 * 60_000L).toString()))
        assertEquals(BigDecimal("180.00"), amount(eventTime = (now + 60_000L).toString()))
    }
}
