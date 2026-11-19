package com.thunder.linespeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineAnnouncementPolicyTest {
    private fun choose(
        sender: String = "ลูกค้า",
        message: String = "",
        picture: Boolean = false,
        customers: Boolean = true,
        slips: Boolean = true
    ) = LineAnnouncementPolicy.choose(sender, message, picture, customers, slips)

    @Test fun imageNotificationIsAnnouncedWithoutReadingItsContents() {
        assertEquals(
            "มีรูปภาพส่งเข้ามาทาง LINE แล้วเด้อ",
            choose(message = "ส่งรูปภาพ")?.phrase
        )
        assertEquals(
            "มีรูปภาพส่งเข้ามาทาง LINE แล้วเด้อ",
            choose(message = "", picture = true)?.phrase
        )
    }

    @Test fun slipNoticeIsAnnouncedWithoutPaymentVerification() {
        assertEquals(
            "มีสลิปส่งเข้ามาทาง LINE แล้วเด้อ",
            choose(message = "ส่งสลิปมาให้แล้ว")?.phrase
        )
    }

    @Test fun lineTextAmountCanBeReadWithoutClaimingPaymentReceived() {
        assertEquals(
            "มีแจ้งเตือนยอด 180.00 บาทแล้วเด้อ",
            choose(sender = "Thunder", message = "ยอดเงิน 180.00 บาท")?.phrase
        )
        assertEquals(
            "มีแจ้งเตือนยอด 1,250.50 บาทแล้วเด้อ",
            choose(message = "ส่งสลิป 1,250.50 บาท")?.phrase
        )
        assertEquals(
            "มีข้อความจาก Thunder แล้วเด้อ",
            choose(sender = "Thunder", message = "มีรายการใหม่")?.phrase
        )
    }

    @Test fun customerMessagesNeverReadLotteryPicks() {
        assertEquals(
            "สมหญิง ซื้อหวยฮานอย VIP",
            choose(sender = "สมหญิง", message = "ซื้อหวยฮานอย VIP 123 456")?.phrase
        )
        assertEquals(
            "สมหญิง ทัก LINE มา",
            choose(sender = "สมหญิง", message = "สวัสดี")?.phrase
        )
    }

    @Test fun plainLotteryNumbersAreNotTreatedAsMoney() {
        val result = choose(sender = "สมหญิง", message = "ซื้อหวยฮานอย 123 456")
        assertEquals("customer", result?.kind)
        assertNull(choose(sender = "สมหญิง", message = "บัญชี 1234567890", customers = false))
    }

    @Test fun switchesWorkIndependently() {
        assertNull(choose(message = "ส่งรูปภาพ", slips = false))
        assertNull(choose(sender = "Thunder", message = "ยอดเงิน 50 บาท", slips = false))
        assertNull(choose(message = "สวัสดี", customers = false))
        assertEquals(
            "มีรูปภาพส่งเข้ามาทาง LINE แล้วเด้อ",
            choose(message = "ส่งรูปภาพ", customers = false)?.phrase
        )
        assertNull(choose(message = "ส่งรูปภาพ", customers = false, slips = false))
    }
}
