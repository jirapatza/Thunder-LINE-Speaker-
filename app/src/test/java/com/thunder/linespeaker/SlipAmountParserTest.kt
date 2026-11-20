package com.thunder.linespeaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlipAmountParserTest {
    @Test fun readsThaiLabeledSlipAmount() {
        assertEquals("180", SlipAmountParser.find("ยอดเงิน\n180.00 บาท\nค่าธรรมเนียม 0.00 บาท"))
        assertEquals("1250.5", SlipAmountParser.find("จำนวนเงิน: 1,250.50 บาท\nเลขอ้างอิง 823456781"))
    }

    @Test fun readsEnglishSlipsAndCurrencyMark() {
        assertEquals("299.99", SlipAmountParser.find("Transfer amount THB 299.99"))
        assertEquals("80", SlipAmountParser.find("฿80.00"))
    }

    @Test fun excludesFeesBalancesAndUnlabeledNumbers() {
        assertNull(SlipAmountParser.find("ค่าธรรมเนียม 15.00 บาท\nยอดคงเหลือ 999.99 บาท"))
        assertNull(SlipAmountParser.find("1234567890\nเลขที่บัญชี 0123456789"))
        assertNull(SlipAmountParser.find("ค่าโอน 0 บาท"))
    }

    @Test fun singleUnlabeledDecimalRequiresExplicitOptIn() {
        assertNull(SlipAmountParser.find("180.00"))
        assertEquals("180", SlipAmountParser.find("180.00", allowUnlabeledSingleDecimal = true))
        assertNull(SlipAmountParser.find("180.00\n300.00", allowUnlabeledSingleDecimal = true))
    }

    @Test fun rejectsZeroNegativeOrExcessiveAmounts() {
        assertNull(SlipAmountParser.find("ยอดเงิน 0.00 บาท"))
        assertNull(SlipAmountParser.find("ยอดเงิน -50 บาท"))
        assertNull(SlipAmountParser.find("ยอดเงิน 1000000000 บาท"))
        assertNull(SlipAmountParser.find("ยอดเงิน 1234567890"))
    }
}
