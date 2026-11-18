package com.crvdiag.obd

/** Service 09 (vehicle information): VIN, calibration IDs, ECU name. Read-only. */
object VehicleInfo {

    /** Data items of one 09 reply: 49 pid count data… (count = number of items on CAN). */
    fun items(message: EcuMessage, pid: Int, itemLength: Int): List<List<Int>>? {
        val p = message.payload
        if (p.size < 3 || p[0] != 0x49 || p[1] != pid) return null
        val count = p[2]
        val data = p.drop(3)
        return if (count in 1..8 && data.size >= count * itemLength) data.chunked(itemLength).take(count)
        else listOf(data) // non-CAN style without a count byte
    }

    fun ascii(bytes: List<Int>): String =
        bytes.filter { it in 0x20..0x7E }.joinToString("") { it.toChar().toString() }.trim()

    fun vin(message: EcuMessage): String? =
        items(message, 0x02, 17)?.firstOrNull()?.let(::ascii)?.takeIf { it.length == 17 }

    fun calibrationIds(message: EcuMessage): List<String> =
        items(message, 0x04, 16).orEmpty().map(::ascii).filter { it.isNotEmpty() }

    fun ecuName(message: EcuMessage): String? =
        items(message, 0x0A, 20)?.firstOrNull()?.let(::ascii)?.takeIf { it.isNotEmpty() }

    /** North-American VIN check digit (position 9). */
    fun vinCheckDigitValid(vin: String): Boolean {
        if (vin.length != 17) return false
        val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
        var sum = 0
        for ((i, c) in vin.uppercase().withIndex()) {
            sum += (transliterate(c) ?: return false) * weights[i]
        }
        val check = sum % 11
        return vin[8].uppercaseChar() == if (check == 10) 'X' else '0' + check
    }

    private fun transliterate(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        'A', 'J' -> 1
        'B', 'K', 'S' -> 2
        'C', 'L', 'T' -> 3
        'D', 'M', 'U' -> 4
        'E', 'N', 'V' -> 5
        'F', 'W' -> 6
        'G', 'P', 'X' -> 7
        'H', 'Y' -> 8
        'R', 'Z' -> 9
        else -> null // I, O, Q are never used in a VIN
    }
}

/** One service-06 (on-board monitoring) test result. Read-only. */
data class MonitorTest(val mid: Int, val tid: Int, val unitScaling: Int, val value: Int, val min: Int, val max: Int) {
    val passed: Boolean get() = value in min..max
}

object Mode06 {
    /** CAN format: 46 then groups of 9 bytes: MID TID UASID value(2) min(2) max(2). */
    fun parse(message: EcuMessage): List<MonitorTest>? {
        val p = message.payload
        if (p.firstOrNull() != 0x46) return null
        return p.drop(1).chunked(9).filter { it.size == 9 }.map { g ->
            MonitorTest(g[0], g[1], g[2], (g[3] shl 8) or g[4], (g[5] shl 8) or g[6], (g[7] shl 8) or g[8])
        }
    }

    /** Misfire monitor MIDs A2–A5 = cylinders 1–4 (SAE J1979 appendix). */
    val MISFIRE_MIDS = (0xA2..0xA5).toList()
    const val TID_MISFIRE_EWMA_10_CYCLES = 0x0B
    const val TID_MISFIRE_THIS_CYCLE = 0x0C
}

/** Per-cylinder misfire counts from service 06. */
data class MisfireCount(val cylinder: Int, val thisCycle: Int?, val average10Cycles: Int?)

/** Everything a scan report needs from the car (read-only requests only). */
data class ScanData(
    val diagnostics: DiagnosticsReport,
    val permanent: Map<String, List<String>>,
    val vin: String?,
    val calibrationIds: Map<String, List<String>>,
    val ecuNames: Map<String, String>,
    val misfires: List<MisfireCount>,
    /** False when the permanent-code request got no usable answer. */
    val permanentRead: Boolean = true,
)

class ScanReportCollector(
    private val elm: com.crvdiag.elm.Elm327,
    private val protocol: com.crvdiag.elm.ElmProtocol?,
    private val headersOn: Boolean,
) {
    suspend fun collect(onStep: (String) -> Unit = {}): ScanData {
        val diagnostics = DiagnosticsReader(elm, protocol, headersOn).read(onStep)
        val problems = diagnostics.problems.toMutableList()

        suspend fun messages(cmd: String, what: String): List<EcuMessage> =
            when (val r = elm.execute(cmd)) {
                is com.crvdiag.elm.ElmResult.Ok -> ObdMessages.collect(r.lines, protocol, headersOn)
                com.crvdiag.elm.ElmResult.NoData -> emptyList() // optional items (VIN, CAL ID, mode 06 MIDs)
                com.crvdiag.elm.ElmResult.Timeout -> emptyList<EcuMessage>().also { problems += "$what: no reply (timeout)" }
                is com.crvdiag.elm.ElmResult.Error -> emptyList<EcuMessage>().also { problems += "$what: ${r.message}" }
            }

        onStep("Permanent codes (0A)")
        val permanent = messages("0A", "Permanent codes").mapNotNull { m -> Dtcs.fromMessage(m, 0x4A)?.let { m.ecu to it } }.toMap()
        if (permanent.isEmpty()) problems += "Permanent codes: no usable answer (older units may not support them)"
        onStep("VIN (0902)")
        val vin = messages("0902", "VIN").firstNotNullOfOrNull(VehicleInfo::vin)
        onStep("Calibration IDs (0904)")
        val cal = messages("0904", "Calibration IDs").associate { it.ecu to VehicleInfo.calibrationIds(it) }.filterValues { it.isNotEmpty() }
        onStep("Module names (090A)")
        val names = messages("090A", "Module names").mapNotNull { m -> VehicleInfo.ecuName(m)?.let { m.ecu to it } }.toMap()

        val misfires = mutableListOf<MisfireCount>()
        for ((i, mid) in Mode06.MISFIRE_MIDS.withIndex()) {
            onStep("Misfire counter cylinder ${i + 1} (06%02X)".format(mid))
            val tests = messages("06%02X".format(mid), "Misfire cylinder ${i + 1}").flatMap { Mode06.parse(it).orEmpty() }.filter { it.mid == mid }
            if (tests.isNotEmpty()) {
                misfires += MisfireCount(
                    i + 1,
                    tests.firstOrNull { it.tid == Mode06.TID_MISFIRE_THIS_CYCLE }?.value,
                    tests.firstOrNull { it.tid == Mode06.TID_MISFIRE_EWMA_10_CYCLES }?.value,
                )
            }
        }
        return ScanData(diagnostics.copy(problems = problems), permanent, vin, cal, names, misfires, permanentRead = permanent.isNotEmpty())
    }
}
