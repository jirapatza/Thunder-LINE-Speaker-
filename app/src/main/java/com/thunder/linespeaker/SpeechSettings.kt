package com.thunder.linespeaker

import java.math.BigDecimal

internal enum class SpeechType(val defaultText: String) {
    CUSTOMER("มีลูกค้าทักมาใหม่"),
    PICTURE("ลูกค้าโอนเงินแล้ว"),
    VERIFIED("รับเงินแล้ว {ยอดเงิน} บาท")
}

internal object SpeechSettings {
    const val AMOUNT_TOKEN = "{ยอดเงิน}"
    fun validate(type: SpeechType, text: String): Boolean {
        val value = text.trim()
        return value.isNotEmpty() && value.length <= 160 &&
            (type != SpeechType.VERIFIED || (value.contains(AMOUNT_TOKEN) &&
                !value.replace(AMOUNT_TOKEN, "").any { it.isDigit() }))
    }
    fun render(type: SpeechType, enabled: Boolean, text: String, amount: BigDecimal? = null): String? {
        if (!enabled || !validate(type, text)) return null
        if (type != SpeechType.VERIFIED) return text.trim()
        if (amount == null || amount <= BigDecimal.ZERO || amount > BigDecimal("100000000")) return null
        return text.trim().replace(AMOUNT_TOKEN, amount.stripTrailingZeros().toPlainString())
    }
}
