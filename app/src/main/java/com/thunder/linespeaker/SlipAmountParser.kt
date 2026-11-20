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

    private val standaloneDecimal = Regex("(?<![0-9.,-])(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})\\.[0-9]{2}(?![0-9.])")

    fun find(text: String, allowUnlabeledSingleDecimal: Boolean = false): String? {
        val normalized = text.replace('\r', ' ').replace(" ", " ")
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Accept one distinct labeled amount only. A screen may contain several
        // slips or chat messages, so conflicting candidates are ambiguous.
        val labeledValues = mutableSetOf<String>()
        for (i in lines.indices) {
            if (ignoreLine.containsMatchIn(lines[i])) continue
            val next = lines.getOrNull(i + 1)
            val slice = if (next != null && !ignoreLine.containsMatchIn(next))
                lines[i] + " " + next else lines[i]
            for (match in labeled.findAll(slice)) {
                valid(match.groups[1]?.value, slice, match.groups[1]?.range?.first ?: 0)
                    ?.let { labeledValues.add(it) }
            }
        }
        if (labeledValues.isNotEmpty()) return labeledValues.singleOrNull()
        val currencyValues = mutableSetOf<String>()
        for (line in lines) {
            if (ignoreLine.containsMatchIn(line)) continue
            for (match in suffixed.findAll(line)) {
                valid(match.groups[1]?.value, line, match.groups[1]?.range?.first ?: 0)
                    ?.let { currencyValues.add(it) }
            }
        }
        if (currencyValues.isNotEmpty()) return currencyValues.singleOrNull()
        if (allowUnlabeledSingleDecimal) {
            // Thai text is not reliably recognized by the Latin OCR model.
            // Only use a single unambiguous x.xx number when the user explicitly
            // selected a slip image or the notification itself mentions a slip.
            val values = lines.filterNot { ignoreLine.containsMatchIn(it) }
                .flatMap { line ->
                    standaloneDecimal.findAll(line).mapNotNull { match ->
                        valid(match.value, line, match.range.first)
                    }.toList()
                }.distinct()
            return values.singleOrNull()
        }
        return null
    }

    private fun valid(raw: String?, source: String, start: Int): String? {
        val candidate = raw ?: return null
        // Reject partial matches inside a phone number or account reference.
        if (start > 0 && (source[start - 1].isDigit() ||
                source[start - 1] == '-' || source[start - 1] == '.')) return null
        val after = start + candidate.length
        if (after < source.length &&
            (source[after].isDigit() || source[after] == ',' || source[after] == '.')) return null
        val amount = candidate.replace(",", "").toBigDecimalOrNull() ?: return null
        if (amount <= BigDecimal.ZERO || amount > BigDecimal("100000000")) return null
        return amount.stripTrailingZeros().toPlainString()
    }
}
