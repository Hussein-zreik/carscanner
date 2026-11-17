package com.crvdiag.obd

import com.crvdiag.elm.CanFrame
import com.crvdiag.elm.ElmProtocol

/** One complete OBD response from one control unit. */
data class EcuMessage(
    /** CAN response ID ("7E8") with headers on, otherwise "ECU 1", "ECU 2", … */
    val ecu: String,
    val payload: List<Int>,
)

/** ISO 15765-2 (ISO-TP) reassembly of the frames the ELM prints with headers on. */
object IsoTp {
    fun reassemble(frames: List<CanFrame>): List<EcuMessage> {
        val result = mutableListOf<EcuMessage>()
        val buffers = mutableMapOf<String, MutableList<Int>>()
        val expected = mutableMapOf<String, Int>()
        for (frame in frames) {
            val pci = frame.bytes.firstOrNull() ?: continue
            when (pci shr 4) {
                0x0 -> frame.singleFramePayload?.let { result += EcuMessage(frame.header, it) }
                0x1 -> if (frame.bytes.size >= 2) {
                    expected[frame.header] = ((pci and 0x0F) shl 8) or frame.bytes[1]
                    buffers[frame.header] = frame.bytes.drop(2).toMutableList()
                }
                0x2 -> {
                    val buffer = buffers[frame.header] ?: continue
                    buffer += frame.bytes.drop(1)
                    val length = expected.getValue(frame.header)
                    if (buffer.size >= length) {
                        result += EcuMessage(frame.header, buffer.take(length))
                        buffers.remove(frame.header)
                    }
                }
                else -> Unit // 0x3 flow control: handled by the adapter
            }
        }
        return result
    }
}

object ObdMessages {
    private val hex = Regex("[0-9A-F]+")
    private val indexed = Regex("([0-9A-F]):([0-9A-F]*)")

    /** Splits the reply lines of one request into per-ECU messages. */
    fun collect(lines: List<String>, protocol: ElmProtocol?, headersOn: Boolean): List<EcuMessage> {
        if (headersOn && protocol != null && protocol.isCan) {
            val frames = lines.mapNotNull { CanFrame.parse(it, protocol) }
            if (frames.size == lines.size) return IsoTp.reassemble(frames)
        }
        return headerless(lines)
    }

    /**
     * Headers off: single frames are one line each; multi-frame replies come as a length line
     * ("00A") followed by "0: …", "1: …". Several ECUs cannot be told apart reliably.
     */
    private fun headerless(lines: List<String>): List<EcuMessage> {
        val messages = mutableListOf<MutableList<Int>>()
        val lengths = mutableListOf<Int?>()
        var declaredLength: Int? = null
        for (line in lines) {
            val text = line.filterNot { it.isWhitespace() }.uppercase()
            if (text.length == 3 && hex.matches(text)) { // ISO-TP length line, e.g. "00A"
                declaredLength = text.toInt(16)
                continue
            }
            val m = indexed.matchEntire(text)
            val (index, data) = if (m != null) m.groupValues[1].toInt(16) to m.groupValues[2] else null to text
            if (!hex.matches(data) || data.length % 2 != 0) continue
            if (index == null || index == 0 || messages.isEmpty()) {
                messages += mutableListOf<Int>()
                lengths += if (index != null) declaredLength else null
                declaredLength = null
            }
            messages.last() += data.chunked(2).map { it.toInt(16) }
        }
        // Multi-frame lines are padded; trim each message to its declared length.
        return messages.mapIndexed { i, bytes ->
            EcuMessage("ECU ${i + 1}", lengths[i]?.let { bytes.take(it) } ?: bytes)
        }
    }
}
