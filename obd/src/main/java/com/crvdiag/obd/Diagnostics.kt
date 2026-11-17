package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.ElmResult

data class FreezeFrame(val dtc: String, val values: List<PidValue>)

data class DiagnosticsReport(
    val readiness: Map<String, Readiness>,
    val stored: Map<String, List<String>>,
    val pending: Map<String, List<String>>,
    val freezeFrames: Map<String, FreezeFrame>,
    /** Requests that failed (timeouts, adapter errors); data shown may be incomplete. */
    val problems: List<String>,
)

/**
 * Read-only diagnostics: readiness (0101), stored codes (03), pending codes (07) and the
 * freeze frame (02, frame 0). Requests go to the default functional address (7DF);
 * answers are grouped per ECU.
 */
class DiagnosticsReader(
    private val elm: Elm327,
    private val protocol: ElmProtocol?,
    private val headersOn: Boolean,
) {
    suspend fun read(onStep: (String) -> Unit = {}): DiagnosticsReport {
        val problems = mutableListOf<String>()

        suspend fun messages(cmd: String, what: String): List<EcuMessage> =
            when (val r = elm.execute(cmd)) {
                is ElmResult.Ok -> ObdMessages.collect(r.lines, protocol, headersOn)
                ElmResult.NoData -> emptyList()
                ElmResult.Timeout -> emptyList<EcuMessage>().also { problems += "$what: no reply (timeout)" }
                is ElmResult.Error -> emptyList<EcuMessage>().also { problems += "$what: ${r.message}" }
            }

        // Live data may have left the adapter addressing one unit: talk to all of them again.
        if (protocol?.isCan == true) elm.execute("ATSH7DF")

        onStep("Readiness (0101)")
        val readiness = messages("0101", "Readiness")
            .mapNotNull { m -> Readiness.fromMessage(m)?.let { m.ecu to it } }.toMap()

        onStep("Stored codes (03)")
        val stored = messages("03", "Stored codes")
            .mapNotNull { m -> Dtcs.fromMessage(m, 0x43)?.let { m.ecu to it } }.toMap()

        onStep("Pending codes (07)")
        val pending = messages("07", "Pending codes")
            .mapNotNull { m -> Dtcs.fromMessage(m, 0x47)?.let { m.ecu to it } }.toMap()

        onStep("Freeze frame (0202)")
        val frameDtcs = messages("020200", "Freeze frame").mapNotNull { m ->
            val p = m.payload
            if (p.size >= 5 && p[0] == 0x42 && p[1] == 0x02) Dtcs.decode(p[3], p[4])?.let { m.ecu to it } else null
        }.toMap()

        val freezeFrames = mutableMapOf<String, FreezeFrame>()
        if (frameDtcs.isNotEmpty()) {
            val values = mutableMapOf<String, MutableList<PidValue>>()
            for (pid in FREEZE_FRAME_PIDS) {
                onStep("Freeze frame PID %02X".format(pid))
                for (m in messages("02%02X00".format(pid), "Freeze frame PID %02X".format(pid))) {
                    val p = m.payload
                    if (p.size >= 4 && p[0] == 0x42 && p[1] == pid && m.ecu in frameDtcs) {
                        Pids.decode(pid, p.drop(3))?.let { values.getOrPut(m.ecu) { mutableListOf() } += it }
                    }
                }
            }
            frameDtcs.forEach { (ecu, dtc) -> freezeFrames[ecu] = FreezeFrame(dtc, values[ecu].orEmpty()) }
        }
        return DiagnosticsReport(readiness, stored, pending, freezeFrames, problems)
    }

    companion object {
        val FREEZE_FRAME_PIDS = listOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0F, 0x11)
    }
}
