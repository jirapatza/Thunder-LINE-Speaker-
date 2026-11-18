package com.thunder.linespeaker

import org.junit.Assert.*
import org.junit.Test

class AmountParserTest {
    @Test fun handlesAmounts() {
        assertEquals("180.00", AmountParser.findAmount("Thunder ยอดเงิน 180.00 บาท"))
        assertEquals("1,250.50", AmountParser.findAmount("Amount: THB 1,250.50"))
        assertEquals("80", AmountParser.findAmount("รับเงิน 80 บาท"))
        assertEquals("42.00", AmountParser.findAmount("42.00 THB"))
    }
    @Test fun rejectsUnlabeledNumbers() {
        assertNull(AmountParser.findAmount("ลูกค้าซื้อหวย 123 456"))
        assertNull(AmountParser.findAmount("บัญชี 1234567890"))
        assertNull(AmountParser.findAmount(""))
        assertNull(AmountParser.findAmount("ยอดเงิน 0 บาท"))
    }
}
