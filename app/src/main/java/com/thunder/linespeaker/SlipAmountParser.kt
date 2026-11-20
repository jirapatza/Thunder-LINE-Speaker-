package com.thunder.linespeaker

import java.math.BigDecimal

/**
 * Best-effort extraction of the amount PRINTED on a slip. Never treats OCR as
 * bank confirmation. Prefer labeled totals over balances, fees and IDs.
 */
internal object SlipAmountParser {
    private const val NUMBER = "([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,8}(?:\\.[0-9]{1,2})?)"
    private val labeled = Regex(
        "(?:จำนวนเงิน|ยอดเงิน|ยอดโอน|ยอดชำระ|เงินจำนวน|จำนวน|โอนเงิน|transfer\\s*amount|amount|total|฿)\\s*[:：=]?\\s*(?:฿|THB)?\\s*$NUMBER",
        RegexOption.IGNORE_CASE
    )
    private val suffixed = Regex("$NUMBER\\s*(?:บาท|THB|฿)", RegexOption.IGNORE_CASE)
    private val ignoreLine = Regex(
        "ค่าธรรมเนียม|fee|คงเหลือ|balance|เลขที่บัญชี|account|เลขอ้างอิง|reference",
        RegexOption.IGNORE_CASE
    )

    fun find(text: String): String? {
        val normalized = text.replace('\r', ' ').replace(" ", " ")
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Number may follow the "amount" heading on the next OCR line.
        for (i in lines.indices) {
            if (ignoreLine.containsMatchIn(lines[i])) continue
            val slice = lines.subList(i, minOf(i + 2, lines.size)).joinToString(" ")
            val match = labeled.find(slice) ?: continue
            valid(match.groups[1]?.value, slice, match.groups[1]?.range?.first ?: 0)?.let { return it }
        }
        for (line in lines) {
            if (ignoreLine.containsMatchIn(line)) continue
            for (match in suffixed.findAll(line)) {
                valid(match.groups[1]?.value, line, match.groups[1]?.range?.first ?: 0)?.let { return it }
            }
        }
        return null
    }

    private fun valid(raw: String?, source: String, start: Int): String? {
        val candidate = raw ?: return null
        // Reject partial matches inside a phone number or account reference.
        if (start > 0 && (source[start - 1].isDigit() ||
                source[start - 1] == '-' || source[start - 1] == '.')) return null
        val amount = candidate.replace(",", "").toBigDecimalOrNull() ?: return null
        if (amount <= BigDecimal.ZERO || amount > BigDecimal("100000000")) return null
        return amount.stripTrailingZeros().toPlainString()
    }
}
