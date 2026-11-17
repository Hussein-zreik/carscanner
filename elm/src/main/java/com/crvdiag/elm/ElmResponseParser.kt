package com.crvdiag.elm

/**
 * Turns one complete adapter reply (everything before the '>' prompt) into an [ElmResult].
 * Handles what the V03H4 clone actually does (see testdata/): echoed command as its own line,
 * "SEARCHING...", blank lines, trailing spaces, several answer lines (one per ECU).
 */
object ElmResponseParser {

    private val errorPrefixes = listOf(
        "CAN ERROR", "BUS ERROR", "BUS BUSY", "BUFFER FULL", "DATA ERROR", "<DATA ERROR",
        "<RX ERROR", "FB ERROR", "LV RESET", "STOPPED", "UNABLE TO CONNECT", "ACT ALERT", "ERR",
    )
    private val busInitOk = Regex("BUS INIT:?\\s*\\.*\\s*OK", RegexOption.IGNORE_CASE)

    fun parse(command: String, frame: String): ElmResult {
        val lines = frame.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        // Echo (ATE1): the first line repeats the command we sent.
        if (lines.isNotEmpty() && CommandPolicy.normalize(lines.first()) == CommandPolicy.normalize(command)) {
            lines.removeAt(0)
        }
        lines.removeAll { it.startsWith("SEARCHING", ignoreCase = true) || busInitOk.matches(it) }

        lines.firstOrNull(::isError)?.let { return ElmResult.Error(it) }
        val data = lines.filterNot { it.equals("NO DATA", ignoreCase = true) }
        if (data.isEmpty() && lines.isNotEmpty()) return ElmResult.NoData
        return ElmResult.Ok(data)
    }

    private fun isError(line: String): Boolean {
        val upper = line.uppercase()
        return upper == "?" ||
            (upper.startsWith("BUS INIT") && "ERROR" in upper) ||
            errorPrefixes.any { upper.startsWith(it) }
    }
}

/** Collects text chunks until the ELM '>' prompt; returns the complete reply before it. */
class PromptFramer {
    private val buffer = StringBuilder()

    /** Returns the finished reply when [chunk] contains the prompt, otherwise null. */
    fun append(chunk: String): String? {
        buffer.append(chunk)
        val prompt = buffer.indexOf(">")
        if (prompt < 0) return null
        val frame = buffer.substring(0, prompt)
        buffer.delete(0, prompt + 1)
        return frame
    }
}
