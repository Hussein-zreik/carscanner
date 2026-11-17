package com.crvdiag.elm

/** OBD protocols as numbered by the ELM327 (ATSP / ATDPN), per the ELM327 datasheet. */
enum class ElmProtocol(val code: Char, val label: String, val isCan: Boolean, val headerHexDigits: Int) {
    AUTO('0', "Automatic", false, 0),
    J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)", false, 6),
    J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)", false, 6),
    ISO_9141_2('3', "ISO 9141-2 (5 baud init)", false, 6),
    KWP_5BAUD('4', "ISO 14230-4 KWP (5 baud init)", false, 6),
    KWP_FAST('5', "ISO 14230-4 KWP (fast init)", false, 6),
    CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", true, 3),
    CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", true, 8),
    CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", true, 3),
    CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", true, 8),
    J1939('A', "SAE J1939 CAN (29 bit, 250 kbaud)", true, 8),
    ;

    companion object {
        fun byCode(code: Char): ElmProtocol? = entries.firstOrNull { it.code == code.uppercaseChar() }

        /** Parses an ATDPN reply: "A6" = chosen automatically, protocol 6; "6" = set explicitly. */
        fun parseDpn(reply: String): DetectedProtocol? {
            val text = reply.trim().uppercase()
            return when {
                text.length == 2 && text[0] == 'A' -> byCode(text[1])?.let { DetectedProtocol(it, automatic = true) }
                text.length == 1 -> byCode(text[0])?.let { DetectedProtocol(it, automatic = false) }
                else -> null
            }
        }
    }
}

data class DetectedProtocol(val protocol: ElmProtocol, val automatic: Boolean)

/** One CAN frame as printed by the ELM with headers on (ATH1): header + PCI byte + data. */
data class CanFrame(val header: String, val bytes: List<Int>) {
    /** ISO-TP single frame (PCI 0x0N): the N payload bytes; null for multi-frame parts. */
    val singleFramePayload: List<Int>?
        get() {
            val pci = bytes.firstOrNull() ?: return null
            if (pci shr 4 != 0) return null
            val length = pci and 0x0F
            return if (length in 1..bytes.size - 1) bytes.subList(1, 1 + length) else null
        }

    companion object {
        private val hex = Regex("[0-9A-F]+")

        /** Parses "7E8 06 41 00 BE 3F A8 13" or "7E8064100BE3FA813"; null if it doesn't fit. */
        fun parse(line: String, protocol: ElmProtocol): CanFrame? {
            if (!protocol.isCan) return null
            val text = line.filterNot { it.isWhitespace() }.uppercase()
            val n = protocol.headerHexDigits
            if (!hex.matches(text) || text.length <= n || (text.length - n) % 2 != 0) return null
            return CanFrame(text.take(n), text.drop(n).chunked(2).map { it.toInt(16) })
        }
    }
}
