package com.crvdiag.honda.capture

import com.crvdiag.elm.CommandPolicy
import com.crvdiag.transport.RecordedLog

/**
 * What a capture contains, grouped for Phase 2: the non-standard (Honda) read requests with the
 * header that was active when they were sent, how often, and one example reply.
 * Standard OBD requests are only counted.
 */
data class CaptureSummary(
    val honda: List<HondaRequest>,
    val standardRequests: Int,
    val blocked: List<String>,
    val headers: List<String>,
) {
    data class HondaRequest(val header: String, val request: String, val count: Int, val exampleReply: String)

    /** '#' comment lines for the end of the capture file (testdata format). */
    fun asComments(): List<String> = buildList {
        add("# ===== Capture summary =====")
        add("# Standard OBD requests: $standardRequests · Honda/other read requests: ${honda.size} distinct · Blocked: ${blocked.size}")
        if (headers.isNotEmpty()) add("# Headers set by the client: ${headers.joinToString()}")
        honda.forEach { add("# HONDA ${it.header} ${it.request} x${it.count} -> ${it.exampleReply}") }
        blocked.distinct().forEach { add("# BLOCKED $it") }
    }

    companion object {
        private val standard = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A)
        private val hex = Regex("([0-9A-F]{2})+")

        fun of(lines: List<RecordedLog.Line>): CaptureSummary {
            var header = "default"
            var standardCount = 0
            val headers = linkedSetOf<String>()
            val counts = linkedMapOf<Pair<String, String>, Int>()
            val examples = mutableMapOf<Pair<String, String>, String>()
            for (ex in RecordedLog.exchanges(lines)) {
                val cmd = CommandPolicy.normalize(ex.command)
                when {
                    cmd.startsWith("ATSH") -> header = cmd.removePrefix("ATSH").also { headers += it }
                    cmd == "ATZ" || cmd == "ATD" -> header = "default"
                    hex.matches(cmd) -> {
                        if (cmd.take(2).toInt(16) in standard) {
                            standardCount++
                        } else {
                            val key = header to cmd
                            counts[key] = (counts[key] ?: 0) + 1
                            val reply = ex.reply.substringBefore('>').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
                            if (key !in examples && reply.isNotEmpty() && "NO DATA" !in reply) examples[key] = reply
                        }
                    }
                }
            }
            val blocked = lines.filter { it.direction == com.crvdiag.transport.TrafficLog.Direction.INFO && it.text.startsWith("BLOCKED ") }
                .map { it.text.removePrefix("BLOCKED ").substringBefore(" (") }
            return CaptureSummary(
                counts.map { (key, n) -> HondaRequest(key.first, key.second, n, examples[key] ?: "no answer") },
                standardCount, blocked, headers.toList(),
            )
        }
    }
}
