package com.crvdiag.app.cartest

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.honda.CarProfile
import com.crvdiag.obd.EcuInfo
import com.crvdiag.obd.LiveDataSession
import com.crvdiag.obd.ScanData
import com.crvdiag.obd.ScanReportCollector
import com.crvdiag.obd.VehicleInfo

enum class StepStatus { Pass, Warn, Fail, Skipped }

data class StepResult(val title: String, val status: StepStatus, val detail: String)

data class CarTestOutcome(
    val results: List<StepResult>,
    val scan: ScanData?,
    val liveSamples: Int,
    val updatesPerSecond: Double,
)

/**
 * The car-side checks of the one-tap Car test (read-only; the link must already be Ready).
 * Android-free so it runs against replayed recordings in unit tests.
 */
class CarTestRunner(
    private val elm: Elm327,
    private val protocol: ElmProtocol?,
    private val headersOn: Boolean,
    private val ecus: List<EcuInfo>,
    private val liveDurationMs: Long = 20_000,
    private val maxLiveSamples: Int = Int.MAX_VALUE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(onStep: (String) -> Unit): CarTestOutcome {
        val results = mutableListOf<StepResult>()

        // 1. Network
        onStep("Car network")
        results += if (protocol?.isCan == true && ecus.isNotEmpty()) {
            StepResult("Car network", StepStatus.Pass,
                "${protocol.label}; ${ecus.size} unit(s): " + ecus.joinToString { it.responseId ?: "?" })
        } else {
            StepResult("Car network", StepStatus.Fail, "No CAN network or no unit answered (protocol ${protocol?.code ?: "?"}).")
        }

        // 2–4. Codes, VIN, permanent codes + misfires (one read-only scan)
        onStep("Trouble codes, VIN, self-tests")
        val scan = runCatching { ScanReportCollector(elm, protocol, headersOn).collect(onStep) }.getOrNull()
        val d = scan?.diagnostics
        results += when {
            d == null -> StepResult("Trouble codes & readiness", StepStatus.Fail, "The scan could not run.")
            !d.readinessRead && !d.storedRead -> StepResult("Trouble codes & readiness", StepStatus.Fail,
                "The car did not answer the code requests. " + d.problems.joinToString("; "))
            else -> {
                val stored = d.stored.values.flatten()
                val pending = d.pending.values.flatten()
                val light = when (d.milOn) { true -> "Light ON"; false -> "Light OFF"; null -> "Light unknown" }
                val text = "$light · stored: ${stored.ifEmpty { listOf("none") }.joinToString()} · pending: ${pending.ifEmpty { listOf("none") }.joinToString()}"
                val ok = d.readinessRead && d.storedRead && d.pendingRead && !d.milWithoutStoredCode
                StepResult("Trouble codes & readiness", if (ok) StepStatus.Pass else StepStatus.Warn,
                    text + if (ok) "" else " · incomplete: " + d.problems.joinToString("; ").ifEmpty { "light ON but no stored code received" })
            }
        }
        val vin = scan?.vin
        results += when {
            vin == null -> StepResult("VIN", StepStatus.Warn, "The car did not report its VIN.")
            vin == CarProfile.VIN && VehicleInfo.vinCheckDigitValid(vin) -> StepResult("VIN", StepStatus.Pass, "$vin (matches this car, check digit valid)")
            else -> StepResult("VIN", StepStatus.Warn, "$vin does not match ${CarProfile.VIN} or has an invalid check digit.")
        }
        results += if (scan == null) StepResult("Permanent codes & misfire counters", StepStatus.Skipped, "Scan did not run.")
        else StepResult(
            "Permanent codes & misfire counters",
            if (scan.permanentRead && scan.misfires.isNotEmpty()) StepStatus.Pass else StepStatus.Warn,
            listOf(
                if (scan.permanentRead) "permanent: ${scan.permanent.values.flatten().ifEmpty { listOf("none") }.joinToString()}" else "permanent codes not read",
                if (scan.misfires.isNotEmpty()) "misfires: " + scan.misfires.joinToString { "cyl ${it.cylinder}=${it.thisCycle ?: "?"}" } else "misfire counters not read",
            ).joinToString(" · "),
        )

        // 5. Live data
        onStep("Live data")
        val engine = ecus.firstOrNull { 0x06 in it.supportedPids && 0x10 in it.supportedPids } ?: ecus.firstOrNull()
        val live = LiveDataSession(elm, protocol, headersOn, engine?.requestId, clock)
        var samples = 0
        var withValues = 0
        val seen = mutableSetOf<Int>()
        var rate = 0.0
        if (live.begin()) {
            val pids = LiveDataSession.DEFAULT_PIDS.filter { it in live.supportedPids().ifEmpty { engine?.supportedPids.orEmpty() } }
            val start = clock()
            while (samples < maxLiveSamples && clock() - start < liveDurationMs && !elm.closed.isCompleted) {
                val s = live.sample(pids)
                samples++
                if (s.values.isNotEmpty()) withValues++
                seen += s.values.keys
                onStep("Live data: $samples updates, ${seen.size} readings")
            }
            val span = (clock() - start).coerceAtLeast(1)
            rate = samples * 1000.0 / span
            live.end()
        }
        results += when {
            samples == 0 -> StepResult("Live data", StepStatus.Fail, "Live data could not start.")
            withValues == 0 -> StepResult("Live data", StepStatus.Fail, "$samples requests, no values received.")
            else -> StepResult(
                "Live data",
                if (withValues >= samples * 0.7 && seen.size >= 10) StepStatus.Pass else StepStatus.Warn,
                "%d updates (%.1f/s), %d different readings, %d%% answered, %s".format(
                    samples, rate, seen.size, withValues * 100 / samples,
                    if (live.multiPidWorks) "several readings per request" else "one reading per request (slower)",
                ),
            )
        }
        return CarTestOutcome(results, scan, samples, rate)
    }
}
