package com.thunder.linespeaker

/**
 * Converts text already visible in a LINE notification into a short, local
 * voice announcement. This does not verify payments or call any service.
 * In particular, a LINE contact name (including "Thunder") is not proof
 * of the sender's identity.
 */
internal object LineAnnouncementPolicy {
    data class Alert(val phrase: String, val kind: String)

    private val pictureHints = listOf(
        "ส่งรูป", "ส่งภาพ", "รูปภาพ", "รูปถ่าย", "ภาพถ่าย",
        "sent a photo", "photo", "picture", "image", "[รูป]"
    )
    private val slipHints = listOf("สลิป", "slip", "หลักฐานการโอน")
    private val amountHints = listOf(
        "ยอด", "เงิน", "โอน", "ชำระ", "amount", "total", "thb", "฿"
    )
    private val lotteries = listOf(
        "ฮานอย VIP", "ฮานอยพิเศษ", "ฮานอยสามัคคี",
        "ฮานอย", "ลาวพัฒนา", "ลาวประชาชน", "ลาวอาเซียน",
        "รัฐบาล", "หุ้น"
    )

    fun choose(
        sender: String,
        message: String,
        hasPicture: Boolean,
        customerVoice: Boolean,
        slipVoice: Boolean
    ): Alert? {
        val name = sender.trim().take(40)
        val body = message.trim()
        val fromThunderLabel = name.contains("Thunder", ignoreCase = true)
        val mentionsSlip = slipHints.any { body.contains(it, ignoreCase = true) }
        val pictureMessage = hasPicture ||
            pictureHints.any { body.contains(it, ignoreCase = true) }

        if (slipVoice) {
            // Never infer a receipt of money. Only say a LINE message mentions
            // an amount, and require contextual words to avoid reading picks.
            if (fromThunderLabel || mentionsSlip ||
                amountHints.any { body.contains(it, ignoreCase = true) }
            ) {
                val amount = AmountParser.findAmount(body)
                if (amount != null) {
                    return Alert("มีแจ้งเตือนยอด $amount บาทแล้วเด้อ", "amount")
                }
            }
            if (mentionsSlip) return Alert("มีสลิปส่งเข้ามาทาง LINE แล้วเด้อ", "slip")
            if (pictureMessage) return Alert("มีรูปภาพส่งเข้ามาทาง LINE แล้วเด้อ", "picture")
            if (fromThunderLabel) return Alert("มีข้อความจาก Thunder แล้วเด้อ", "thunder")
        }

        // A photo with slip voice disabled must not become an unrelated
        // customer-chat announcement through the other toggle.
        if (!customerVoice || body.isBlank() || pictureMessage || mentionsSlip || fromThunderLabel) {
            return null
        }
        if (!body.any { it.isLetter() }) return null
        val lottery = lotteries.firstOrNull { body.contains(it, ignoreCase = true) }
        val phrase = if (lottery != null) {
            "เฮีย ซื้อหวย${lottery} หน่อย"
        } else {
            "เฮีย ลูกค้าทักมาแล้ว"
        }
        return Alert(phrase, "customer")
    }
}
