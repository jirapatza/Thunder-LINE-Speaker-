package com.thunder.linespeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class PaymentAnnouncementPolicyTest {
    @Test fun confirmedFcmPaymentIsEligible() {
        assertEquals(BigDecimal("180.00"), PaymentAnnouncementPolicy.confirmedAmount(
            PaymentAnnouncementPolicy.Source.VERIFIED_FCM,
            "payment_matched", "123456789", "180.00"))
    }

    @Test fun untrustedLineTextAndOcrCanNeverClaimReceivedMoney() {
        for (source in listOf(
            PaymentAnnouncementPolicy.Source.LINE_NOTIFICATION_TEXT,
            PaymentAnnouncementPolicy.Source.IMAGE_OCR
        )) {
            // Even a forged LINE message using exact FCM fields cannot speak
            // as confirmed incoming money.
            assertNull(PaymentAnnouncementPolicy.confirmedAmount(
                source, "payment_matched", "123456789", "180.00"))
        }
    }

    @Test fun malformedOrUnverifiedFirebaseEventsAreRejected() {
        for (type in listOf("slip_unmatched", "duplicate_slip", "payment_pending", null)) {
            assertNull(PaymentAnnouncementPolicy.confirmedAmount(
                PaymentAnnouncementPolicy.Source.VERIFIED_FCM,
                type, "123456789", "180.00"))
        }
        for (id in listOf("", "invalid-id", "1".repeat(33), null)) {
            assertNull(PaymentAnnouncementPolicy.confirmedAmount(
                PaymentAnnouncementPolicy.Source.VERIFIED_FCM,
                "payment_matched", id, "180.00"))
        }
        for (amount in listOf("0", "-1", "100000000.01", "foo", "", null)) {
            assertNull(PaymentAnnouncementPolicy.confirmedAmount(
                PaymentAnnouncementPolicy.Source.VERIFIED_FCM,
                "payment_matched", "123", amount))
        }
    }
}
