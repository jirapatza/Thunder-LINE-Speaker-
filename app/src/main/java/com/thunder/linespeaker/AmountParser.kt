package com.thunder.linespeaker

object AmountParser {
    // Require a recognizable money label, currency prefix or suffix.
    // Reject bare numbers such as account IDs, dates, lottery numbers and chat phone numbers.
    private const val NUM = "([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,8}(?:\\.[0-9]{1,2})?)"
    private val prefix = Regex(
        "(?:ยอด(?:เงิน)?|จำนวนเงิน|รับเงิน|โอนเงิน|amount|total|THB|฿)\\s*[:：=]?\\s*(?:THB|฿)?\\s*$NUM",
        RegexOption.IGNORE_CASE
    )
    private val suffix = Regex("$NUM\\s*(?:บาท|THB|฿)", RegexOption.IGNORE_CASE)

    fun findAmount(text: String): String? {
        val normalized = text.replace('\n', ' ').replace('\r', ' ')
        val matches = listOfNotNull(prefix.find(normalized), suffix.find(normalized))
        for (match in matches.sortedBy { it.range.first }) {
            val candidate = match.groupValues.getOrNull(1).orEmpty()
            val amount = candidate.replace(",", "").toDoubleOrNull() ?: continue
            if (amount > 0.0 && amount <= 100_000_000.0) return candidate
        }
        return null
    }
}
