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
    /** False when the car gave no usable answer. Then "nothing found" must NOT be shown. */
    val readinessRead: Boolean = true,
    val storedRead: Boolean = true,
    val pendingRead: Boolean = true,
) {
    /** Check-engine light: true/false, or null when it could not be read (never assume OFF). */
    val milOn: Boolean? get() = if (!readinessRead || readiness.isEmpty()) null else readiness.values.any { it.milOn }

    /** Light ON but no stored code received: the read is incomplete or the code is not visible here. */
    val milWithoutStoredCode: Boolean get() = milOn == true && storedRead && stored.values.all { it.isEmpty() }
}

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

        /**
         * null = no usable answer. On CAN every unit supporting a service answers it (even
         * "0 codes"), so NO DATA means "no answer", never "nothing found".
         */
        suspend fun messages(cmd: String, what: String, noDataIsProblem: Boolean = true): List<EcuMessage>? =
            when (val r = elm.execute(cmd)) {
                is ElmResult.Ok -> ObdMessages.collect(r.lines, protocol, headersOn)
                ElmResult.NoData -> if (noDataIsProblem) null.also { problems += "$what: no answer from the car" } else emptyList()
                ElmResult.Timeout -> null.also { problems += "$what: no reply (timeout)" }
                is ElmResult.Error -> null.also { problems += "$what: ${r.message}" }
            }

        // 11-bit CAN: live data may have addressed one unit; talk to all of them again.
        // 29-bit CAN: the header is never changed (see ElmProtocol.functionalHeader11).
        protocol?.functionalHeader11?.let { elm.execute("ATSH$it") }

        onStep("Readiness (0101)")
        val readiness = messages("0101", "Readiness").orEmpty()
            .mapNotNull { m -> Readiness.fromMessage(m)?.let { m.ecu to it } }.toMap()
        if (readiness.isEmpty() && problems.none { it.startsWith("Readiness") }) problems += "Readiness: reply not understood"

        onStep("Stored codes (03)")
        val stored = messages("03", "Stored codes").orEmpty()
            .mapNotNull { m -> Dtcs.fromMessage(m, 0x43)?.let { m.ecu to it } }.toMap()
        if (stored.isEmpty() && problems.none { it.startsWith("Stored codes") }) problems += "Stored codes: reply not understood"

        onStep("Pending codes (07)")
        val pending = messages("07", "Pending codes").orEmpty()
            .mapNotNull { m -> Dtcs.fromMessage(m, 0x47)?.let { m.ecu to it } }.toMap()
        if (pending.isEmpty() && problems.none { it.startsWith("Pending codes") }) problems += "Pending codes: reply not understood"

        onStep("Freeze frame (0202)")
        // No freeze frame stored is a normal NO DATA case for some units.
        val frameDtcs = messages("020200", "Freeze frame", noDataIsProblem = false).orEmpty().mapNotNull { m ->
            val p = m.payload
            if (p.size >= 5 && p[0] == 0x42 && p[1] == 0x02) Dtcs.decode(p[3], p[4])?.let { m.ecu to it } else null
        }.toMap()

        val freezeFrames = mutableMapOf<String, FreezeFrame>()
        if (frameDtcs.isNotEmpty()) {
            val values = mutableMapOf<String, MutableList<PidValue>>()
            for (pid in FREEZE_FRAME_PIDS) {
                onStep("Freeze frame PID %02X".format(pid))
                for (m in messages("02%02X00".format(pid), "Freeze frame PID %02X".format(pid), noDataIsProblem = false).orEmpty()) {
                    val p = m.payload
                    if (p.size >= 4 && p[0] == 0x42 && p[1] == pid && m.ecu in frameDtcs) {
                        Pids.decode(pid, p.drop(3))?.let { values.getOrPut(m.ecu) { mutableListOf() } += it }
                    }
                }
            }
            frameDtcs.forEach { (ecu, dtc) -> freezeFrames[ecu] = FreezeFrame(dtc, values[ecu].orEmpty()) }
        }
        return DiagnosticsReport(
            readiness, stored, pending, freezeFrames, problems,
            readinessRead = readiness.isNotEmpty(), storedRead = stored.isNotEmpty(), pendingRead = pending.isNotEmpty(),
        )
    }

    companion object {
        val FREEZE_FRAME_PIDS = listOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0F, 0x11)
    }
}
