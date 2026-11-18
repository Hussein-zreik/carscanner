package com.crvdiag.obd

import com.crvdiag.elm.CanFrame
import com.crvdiag.elm.CommandPolicy
import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.ElmResult
import com.crvdiag.elm.ReplyFilter
import kotlinx.coroutines.delay

/**
 * Which answers belong to an OBD request (SAE J1979): the response service is the request
 * service + 0x40, and for services 01, 02, 06 and 09 the next byte repeats the requested
 * PID / MID / info type. A negative response (7F + service) also belongs to the request.
 *
 * Needed because the V03H4 clone prints late answers after its prompt, where they were taken as
 * the answer to the next request (testdata/2026-11-18_wifi_29bit-late-replies.log).
 */
object ObdReplyMatch {
    private val hexCommand = Regex("([0-9A-F]{2})+")

    /** Response service byte and the allowed second bytes (null = any) for [command]. */
    fun expectation(command: String): Pair<Int, Set<Int>?>? {
        val cmd = CommandPolicy.normalize(command)
        if (!hexCommand.matches(cmd)) return null
        val bytes = cmd.chunked(2).map { it.toInt(16) }
        val service = bytes.first()
        val echoed = when (service) {
            0x01 -> bytes.drop(1).toSet().ifEmpty { null } // one or several PIDs
            0x02, 0x06, 0x09 -> bytes.getOrNull(1)?.let { setOf(it) }
            else -> null // 03, 04, 07, 0A: no parameter
        }
        return (service + 0x40) to echoed
    }

    fun belongs(command: String, payload: List<Int>): Boolean {
        val (response, echoed) = expectation(command) ?: return true
        val request = response - 0x40
        if (payload.size >= 2 && payload[0] == 0x7F && payload[1] == request) return true
        if (payload.firstOrNull() != response) return false
        return echoed == null || (payload.size >= 2 && payload[1] in echoed)
    }

    /** The lines of [lines] that answer [command]; empty when none do. */
    fun filter(command: String, lines: List<String>, protocol: ElmProtocol?, headersOn: Boolean): List<String> {
        if (expectation(command) == null) return lines
        if (headersOn && protocol != null && protocol.isCan) {
            val frames = lines.map { it to CanFrame.parse(it, protocol) }
            if (frames.any { it.second != null }) {
                val kept = mutableListOf<String>()
                val accepting = mutableMapOf<String, Boolean>() // per unit: inside a multi-frame answer of ours
                for ((line, frame) in frames) {
                    frame ?: continue // adapter junk
                    val pci = frame.bytes.firstOrNull() ?: continue
                    when (pci shr 4) {
                        0x0 -> frame.singleFramePayload?.takeIf { belongs(command, it) }?.let { kept += line }
                        0x1 -> belongs(command, frame.bytes.drop(2)).also { ok ->
                            accepting[frame.header] = ok
                            if (ok) kept += line
                        }
                        0x2 -> if (accepting[frame.header] == true) kept += line
                    }
                }
                return kept
            }
        }
        // Headers off: units can't be told apart; keep the reply if any message in it is ours.
        return if (ObdMessages.collect(lines, protocol, headersOn).any { belongs(command, it.payload) }) lines else emptyList()
    }

    fun replyFilter(command: String, protocol: ElmProtocol?, headersOn: Boolean) =
        ReplyFilter { filter(command, it, protocol, headersOn) }
}

/** Sends one OBD request; only answers that belong to it are returned (stale replies skipped). */
suspend fun Elm327.obdRequest(
    command: String,
    protocol: ElmProtocol?,
    headersOn: Boolean,
    timeoutMs: Long = Elm327.DEFAULT_TIMEOUT_MS,
    retries: Int = 1,
    policy: CommandPolicy? = null,
): ElmResult = execute(command, timeoutMs, retries, policy, ObdReplyMatch.replyFilter(command, protocol, headersOn))

/** Pause between attempts of a read the clone dropped. */
const val OBD_RETRY_PAUSE_MS = 300L

/**
 * Read-only request repeated (up to [attempts]) until [complete] accepts the decoded messages:
 * the clone drops replies and sometimes delivers only the first frame of a multi-frame answer
 * (VIN seen on this car). Returns the last result and its messages.
 */
suspend fun Elm327.obdRead(
    command: String,
    protocol: ElmProtocol?,
    headersOn: Boolean,
    attempts: Int = 3,
    timeoutMs: Long = Elm327.DEFAULT_TIMEOUT_MS,
    complete: (List<EcuMessage>) -> Boolean = { it.isNotEmpty() },
): Pair<ElmResult, List<EcuMessage>> {
    var result: ElmResult = ElmResult.Timeout
    var messages = emptyList<EcuMessage>()
    for (attempt in 1..attempts) {
        if (attempt > 1) delay(OBD_RETRY_PAUSE_MS)
        result = obdRequest(command, protocol, headersOn, timeoutMs, retries = 0)
        messages = (result as? ElmResult.Ok)?.let { ObdMessages.collect(it.lines, protocol, headersOn) }.orEmpty()
        if (result is ElmResult.Error && result.message.startsWith("Refused")) break
        if (complete(messages)) break
    }
    return result to messages
}
