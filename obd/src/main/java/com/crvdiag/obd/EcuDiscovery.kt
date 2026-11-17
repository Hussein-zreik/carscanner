package com.crvdiag.obd

import com.crvdiag.elm.CanFrame
import com.crvdiag.elm.ElmProtocol

/** One control unit that answered "which PIDs do you support?" (service 01 PID 00). */
data class EcuInfo(
    /** CAN response ID (e.g. "7E8"), or null when headers were off. */
    val responseId: String?,
    /** Matching physical request ID (7E8 → 7E0), usable with ATSH; null if unknown. */
    val requestId: String?,
    /** Supported service-01 PIDs from this range (1..0x20). */
    val supportedPids: Set<Int>,
)

object SupportedPids {
    /**
     * Decodes the 4-byte bitmask of a "supported PIDs" reply (SAE J1979). Bit 7 of the first
     * byte = PID base+1 … bit 0 of the last byte = PID base+0x20.
     */
    fun decode(base: Int, mask: List<Int>): Set<Int> {
        require(mask.size == 4) { "Supported-PID mask must be 4 bytes" }
        val pids = sortedSetOf<Int>()
        mask.forEachIndexed { byteIndex, byte ->
            for (bit in 0 until 8) {
                if (byte and (0x80 shr bit) != 0) pids += base + byteIndex * 8 + bit + 1
            }
        }
        return pids
    }
}

object EcuDiscovery {

    /** Builds one [EcuInfo] per answering ECU from the reply lines to "0100". */
    fun fromPid00(lines: List<String>, protocol: ElmProtocol?): List<EcuInfo> =
        lines.mapNotNull { line -> withHeader(line, protocol) ?: withoutHeader(line) }

    private fun withHeader(line: String, protocol: ElmProtocol?): EcuInfo? {
        if (protocol == null) return null
        val frame = CanFrame.parse(line, protocol) ?: return null
        val payload = frame.singleFramePayload ?: return null
        val mask = maskFrom(payload) ?: return null
        return EcuInfo(frame.header, requestIdFor(frame.header), SupportedPids.decode(0x00, mask))
    }

    private fun withoutHeader(line: String): EcuInfo? {
        val text = line.filterNot { it.isWhitespace() }.uppercase()
        if (text.length != 12 || !text.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val mask = maskFrom(text.chunked(2).map { it.toInt(16) }) ?: return null
        return EcuInfo(null, null, SupportedPids.decode(0x00, mask))
    }

    /** Payload must be 41 00 + 4 mask bytes. */
    private fun maskFrom(payload: List<Int>): List<Int>? =
        if (payload.size == 6 && payload[0] == 0x41 && payload[1] == 0x00) payload.subList(2, 6) else null

    /** ISO 15765-4: response 7E8–7EF ↔ request 7E0–7E7; 29-bit 18DAF1xx ↔ 18DAxxF1. */
    fun requestIdFor(responseId: String): String? = when {
        responseId.length == 3 && responseId.uppercase() in RESPONSE_11 ->
            (responseId.toInt(16) - 8).toString(16).uppercase()
        responseId.length == 8 && responseId.uppercase().startsWith("18DAF1") ->
            "18DA" + responseId.takeLast(2).uppercase() + "F1"
        else -> null
    }

    /** Inverse of [requestIdFor]: 7E0 → 7E8. */
    fun responseIdFor(requestId: String): String? = when {
        requestId.length == 3 && requestId.uppercase() in REQUEST_11 -> (requestId.toInt(16) + 8).toString(16).uppercase()
        requestId.length == 8 && requestId.uppercase().startsWith("18DA") && requestId.uppercase().endsWith("F1") ->
            "18DAF1" + requestId.substring(4, 6).uppercase()
        else -> null
    }

    private val REQUEST_11 = (0x7E0..0x7E7).map { it.toString(16).uppercase() }.toSet()
    private val RESPONSE_11 = (0x7E8..0x7EF).map { it.toString(16).uppercase() }.toSet()
}
