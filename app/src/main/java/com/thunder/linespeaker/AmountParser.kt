package com.thunder.linespeaker

object AmountParser {
    // Bare numbers (phone numbers, lottery picks, account numbers) must not be payments.
    private const val NUM = "([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,8}(?:\\.[0-9]{1,2})?)"
    private val prefix = Regex(
        "(?:ยอด(?:เงิน)?|จำนวนเงิน|รับเงิน|โอนเงิน|amount|total|THB|฿)\\s*[:：=]?\\s*(?:THB|฿)?\\s*$NUM",
        RegexOption.IGNORE_CASE
    )
    private val suffix = Regex("$NUM\\s*(?:บาท|THB|฿)", RegexOption.IGNORE_CASE)

    fun findAmount(text: String): String? {
        val normalized = text.replace('\n', ' ').replace('\r', ' ')
        val matches = (prefix.findAll(normalized) + suffix.findAll(normalized))
            .sortedBy { it.range.first }
        for (match in matches) {
            val group = match.groups[1] ?: continue
            val start = group.range.first
            val end = group.range.last + 1
            // A partial match inside a longer digit sequence is invalid.
            if (start > 0 && (normalized[start - 1].isDigit() || normalized[start - 1] == ',')) continue
            if (end < normalized.length && (normalized[end].isDigit() || normalized[end] == ',' || normalized[end] == '.')) continue
            val candidate = group.value
            val value = candidate.replace(",", "").toDoubleOrNull() ?: continue
            if (value > 0.0 && value <= 100_000_000.0) return candidate
        }
        return null
    }
}
