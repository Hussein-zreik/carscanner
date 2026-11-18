package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.ElmResult
import kotlin.math.sqrt

/** Mode 06 misfire counters per cylinder (1–4) at one moment. Null = not reported. */
data class MisfireSnapshot(
    val timeMs: Long,
    /** TID 0C: misfires counted in the current/last driving cycle. */
    val current: Map<Int, Int?>,
    /** TID 0B: exponentially weighted average over the last 10 driving cycles. */
    val average10: Map<Int, Int?>,
) {
    val anyReported: Boolean get() = current.values.any { it != null } || average10.values.any { it != null }
}

/** Reads the misfire counters (read-only service 06, MIDs A2–A5). */
class MisfireReader(
    private val elm: Elm327,
    private val protocol: ElmProtocol?,
    private val headersOn: Boolean,
    /** Response ID of the engine computer (e.g. 18DAF111); null = largest reply. */
    private val engineResponseId: String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(): MisfireSnapshot {
        val current = mutableMapOf<Int, Int?>()
        val avg = mutableMapOf<Int, Int?>()
        for ((i, mid) in Mode06.MISFIRE_MIDS.withIndex()) {
            val cylinder = i + 1
            val tests = (elm.execute("06%02X".format(mid)) as? ElmResult.Ok)?.let { r ->
                val msgs = ObdMessages.collect(r.lines, protocol, headersOn)
                (msgs.firstOrNull { it.ecu == engineResponseId } ?: msgs.maxByOrNull { it.payload.size })
                    ?.let(Mode06::parse).orEmpty().filter { it.mid == mid }
            }.orEmpty()
            current[cylinder] = tests.firstOrNull { it.tid == Mode06.TID_MISFIRE_THIS_CYCLE }?.value
            avg[cylinder] = tests.firstOrNull { it.tid == Mode06.TID_MISFIRE_EWMA_10_CYCLES }?.value
        }
        return MisfireSnapshot(clock(), current, avg)
    }
}

enum class Finding { Good, Watch, Info }

data class MisfireFinding(val title: String, val level: Finding, val detail: String)

data class MisfireVerdict(
    /** New misfires per cylinder between the two snapshots (null = not reported). */
    val newMisfires: Map<Int, Int?>,
    val findings: List<MisfireFinding>,
    /** One-line headline for the result card. */
    val headline: String,
)

/**
 * Turns misfire counters + idle readings into plain-language findings for THIS car's rough idle.
 * A guide, not a diagnosis: every rule states what it is based on.
 */
object MisfireAnalysis {
    /** RPM standard deviation above this at warm idle is called unsteady (guide value). */
    const val UNSTEADY_RPM_SD = 40.0
    /** Combined fuel trim beyond ±10 % is a clear lean/rich correction. */
    const val TRIM_LIMIT = 10.0

    /** New misfires per cylinder; a counter that went DOWN was reset (new cycle): count from zero. */
    fun newMisfires(before: MisfireSnapshot, after: MisfireSnapshot): Map<Int, Int?> =
        (1..4).associateWith { c ->
            val a = after.current[c] ?: return@associateWith null
            val b = before.current[c] ?: 0
            if (a >= b) a - b else a
        }

    fun evaluate(
        before: MisfireSnapshot,
        after: MisfireSnapshot,
        rpm: List<Double>,
        shortTrim: List<Double>,
        longTrim: Double?,
        coolant: Double?,
        misfireCodes: List<String>,
    ): MisfireVerdict {
        val fresh = newMisfires(before, after)
        val findings = mutableListOf<MisfireFinding>()
        val counted = fresh.filterValues { it != null }.mapValues { it.value!! }
        val total = counted.values.sum()
        val hit = counted.filterValues { it > 0 }.keys.sorted()

        if (coolant != null && coolant < 75) {
            findings += MisfireFinding("Engine not warm yet", Finding.Info,
                "Coolant ${coolant.toInt()} °C. A cold engine idles faster and less smoothly; repeat once it's above 80 °C.")
        }
        if (misfireCodes.isNotEmpty()) {
            findings += MisfireFinding("Misfire codes stored", Finding.Watch,
                "${misfireCodes.joinToString()}: the engine computer itself has confirmed misfires.")
        }

        // Misfire counters
        val countersMoved = (1..4).any { c -> after.current[c] != null && after.current[c] != before.current[c] }
        when {
            counted.isEmpty() -> findings += MisfireFinding("Misfire counters", Finding.Info,
                "The engine computer didn't report misfire counters (service 06). Use the codes and readings below instead.")
            hit.size == 1 -> findings += MisfireFinding("Misfire on cylinder ${hit.first()}", Finding.Watch,
                "${counted[hit.first()]} misfire(s) during the test, other cylinders clean. One cylinder points to its " +
                    "ignition coil, spark plug or injector. Classic test: swap that coil with a neighbouring cylinder and " +
                    "see if the misfire moves with it.")
            hit.size >= 2 -> findings += MisfireFinding("Misfires on cylinders ${hit.joinToString()}", Finding.Watch,
                "$total misfire(s) spread over several cylinders. Something shared by all cylinders is more likely than " +
                    "one coil: air leak, fuel pressure, or a stuck-open EVAP purge valve (see fuel trims).")
            !countersMoved && rpm.size >= 20 -> findings += MisfireFinding("No new misfires counted", Finding.Good,
                "The counters didn't change. Either there were no misfires, or this engine computer only updates them " +
                    "at the end of a drive: confirm with the drive watch (record a drive).")
            else -> findings += MisfireFinding("No misfires during the test", Finding.Good, "All four cylinders: 0 new misfires.")
        }

        // Idle steadiness
        val sd = if (rpm.size >= 10) stdDev(rpm) else null
        if (sd != null) {
            findings += if (sd > UNSTEADY_RPM_SD) MisfireFinding("Idle speed is unsteady", Finding.Watch,
                "RPM varies ±${sd.toInt()} around ${rpm.average().toInt()} rpm. A healthy warm idle is usually within about ±25 rpm.")
            else MisfireFinding("Idle speed is steady", Finding.Good, "RPM varies only ±${sd.toInt()} around ${rpm.average().toInt()} rpm.")
        }

        // Fuel mixture
        val st = if (shortTrim.isNotEmpty()) shortTrim.average() else null
        if (st != null && longTrim != null) {
            val trim = st + longTrim
            findings += when {
                trim < -TRIM_LIMIT -> MisfireFinding("Engine running rich", Finding.Watch,
                    "Fuel trims ${fmt(trim)} %: the computer is removing fuel. On this car a stuck-open EVAP purge valve " +
                        "(your P145C is about purge flow) is a common cause of a rich, rough idle, especially after refuelling.")
                trim > TRIM_LIMIT -> MisfireFinding("Engine running lean", Finding.Watch,
                    "Fuel trims +${fmt(trim)} %: the computer is adding fuel. Common causes: a vacuum/air leak (hoses, intake " +
                        "gasket, PCV) or weak fuel delivery.")
                else -> MisfireFinding("Fuel mixture normal", Finding.Good, "Fuel trims ${fmt(trim)} % (within ±10 %).")
            }
        }

        // Smooth data but the owner feels shaking → mounts
        val smooth = (sd == null || sd <= UNSTEADY_RPM_SD) && total == 0 && misfireCodes.isEmpty()
        val mixtureOk = st == null || longTrim == null || kotlin.math.abs(st + longTrim) <= TRIM_LIMIT
        if (smooth && mixtureOk && counted.isNotEmpty()) {
            findings += MisfireFinding("If it still feels shaky", Finding.Info,
                "The engine data looks smooth. A vibration you feel with smooth data is often worn engine or transmission " +
                    "mounts (common on this CR-V generation), usually worse in Drive at a stop. A shop can check them visually.")
        }

        val headline = when {
            misfireCodes.isNotEmpty() || total > 0 -> if (hit.size == 1) "Misfire found on cylinder ${hit.first()}" else "Misfires found"
            findings.any { it.level == Finding.Watch } -> "No misfires, but something needs attention"
            counted.isEmpty() -> "Misfire counters not available"
            else -> "No misfires found"
        }
        return MisfireVerdict(fresh, findings, headline)
    }

    fun misfireCodesIn(codes: List<String>): List<String> = codes.filter { it in setOf("P0300", "P0301", "P0302", "P0303", "P0304") }

    private fun stdDev(v: List<Double>): Double {
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    private fun fmt(v: Double) = "%.1f".format(v)
}
