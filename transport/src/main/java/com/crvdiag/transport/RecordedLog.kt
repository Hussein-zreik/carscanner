package com.crvdiag.transport

import com.crvdiag.transport.TrafficLog.Direction

/**
 * Reads the traffic-log text format used in testdata/ ("HH:mm:ss.SSS TX|RX|INFO text",
 * control characters escaped, '#' comment lines). Basis for tests and FakeTransport replay.
 */
object RecordedLog {

    data class Line(val time: String, val direction: Direction, val text: String)

    /** One command and every reply chunk received before the next command. */
    data class Exchange(val command: String, val replyChunks: List<String>) {
        val reply: String get() = replyChunks.joinToString("")
    }

    fun parse(content: String): List<Line> = content.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { raw ->
            val parts = raw.split(' ', limit = 3)
            require(parts.size >= 2) { "Bad log line: $raw" }
            Line(parts[0], Direction.valueOf(parts[1]), unescape(parts.getOrElse(2) { "" }))
        }
        .toList()

    fun exchanges(lines: List<Line>): List<Exchange> {
        val result = mutableListOf<Exchange>()
        var command: String? = null
        val chunks = mutableListOf<String>()
        for (line in lines) {
            when (line.direction) {
                Direction.TX -> {
                    command?.let { result += Exchange(it, chunks.toList()) }
                    command = line.text
                    chunks.clear()
                }
                Direction.RX -> if (command != null) chunks += line.text
                Direction.INFO -> Unit
            }
        }
        command?.let { result += Exchange(it, chunks.toList()) }
        return result
    }

    /** Reverses [TrafficLog.escape]. */
    fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'r' -> { append('\r'); i += 2; continue }
                    'n' -> { append('\n'); i += 2; continue }
                    't' -> { append('\t'); i += 2; continue }
                    'x' -> if (i + 3 < text.length) {
                        append(text.substring(i + 2, i + 4).toInt(16).toChar()); i += 4; continue
                    }
                }
            }
            append(c)
            i++
        }
    }
}
