package com.crvdiag.app.tests

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.elm.ElmResult
import com.crvdiag.obd.BatteryAnalysis
import com.crvdiag.obd.DiagFinding
import com.crvdiag.obd.DiagnosticsReader
import com.crvdiag.obd.EvapAnalysis
import com.crvdiag.obd.Finding
import com.crvdiag.obd.FuelTrimAnalysis
import com.crvdiag.obd.LiveChecks
import com.crvdiag.obd.LiveDataSession
import com.crvdiag.obd.MonitorResults
import com.crvdiag.obd.Mode06Catalog
import com.crvdiag.obd.Readiness
import com.crvdiag.obd.SelfTestReader
import com.crvdiag.obd.TestVerdict
import com.crvdiag.transport.TrafficLog.Direction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What one step measured. */
class PhaseData {
    val values = mutableMapOf<Int, MutableList<Double>>()
    val volts = mutableListOf<Double>()
    fun avg(pid: Int) = values[pid]?.takeIf { it.isNotEmpty() }?.average()
}

data class GuidedState(
    val kind: TestKind? = null,
    val phaseIndex: Int = 0,
    val waitingForUser: Boolean = false,
    val measuring: Boolean = false,
    val secondsLeft: Int = 0,
    val live: String? = null,
    val doneSteps: Int = 0,
    val verdict: TestVerdict? = null,
    val selfTests: List<MonitorResults> = emptyList(),
    val readiness: Readiness? = null,
    val message: String? = null,
) {
    val phases: List<Phase> get() = kind?.let(TestPlans::phases).orEmpty()
    val running: Boolean get() = kind != null && verdict == null && message == null && (waitingForUser || measuring || doneSteps < phases.size)
}

/** Runs the guided tests step by step (read-only requests only). */
class TestsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(GuidedState())
    val state: StateFlow<GuidedState> = _state.asStateFlow()

    private var job: Job? = null
    private var userReady: CompletableDeferred<Unit>? = null

    /** Screenshot tests only. */
    internal fun showPreview(s: GuidedState) {
        _state.value = s
    }

    fun next() {
        userReady?.complete(Unit)
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(waitingForUser = false, measuring = false, message = "Test stopped.") }
    }

    fun reset() {
        job?.cancel()
        _state.value = GuidedState()
    }

    fun start(kind: TestKind, connection: ConnectionViewModel, recordingActive: Boolean) {
        if (job?.isActive == true) return
        val phases = TestPlans.phases(kind)
        _state.value = GuidedState(kind = kind)
        val elm = connection.elmOrNull()
        val ready = connection.carLink.value as? CarLinkState.Ready
        val ignitionOffOk = phases.first().worksIgnitionOff
        when {
            recordingActive -> return fail("A drive is being recorded. Stop the recording first.")
            elm == null -> return fail("Connect to the adapter first (Dashboard → Connect).")
            ready == null && !ignitionOffOk -> return fail("The car isn't answering. Ignition ON (engine running for most tests), then connect.")
        }
        val log = connection.log
        val protocol = ready?.report?.detected?.protocol
        val headersOn = ready?.report?.headersOn ?: true
        val engine = ready?.ecus?.firstOrNull { 0x06 in it.supportedPids && 0x10 in it.supportedPids } ?: ready?.ecus?.firstOrNull()

        job = viewModelScope.launch {
            log.add(Direction.INFO, "Guided test: ${kind.title} started")
            val data = mutableMapOf<String, PhaseData>()
            var selfTests = emptyList<MonitorResults>()
            var readiness: Readiness? = null
            var ignitionOnAtRest = false
            for ((i, phase) in phases.withIndex()) {
                if (phase.waitForUser) {
                    userReady = CompletableDeferred()
                    _state.update { it.copy(phaseIndex = i, waitingForUser = true, measuring = false, live = null) }
                    userReady!!.await()
                }
                _state.update { it.copy(phaseIndex = i, waitingForUser = false, measuring = true, secondsLeft = phase.seconds) }
                val linkNow = connection.carLink.value as? CarLinkState.Ready
                val d = PhaseData()
                if (phase.key == "rest") {
                    // The engine computer only answers with the ignition ON: tells true resting vs key-on voltage.
                    ignitionOnAtRest = elm.execute("0100", timeoutMs = 1_500, retries = 0) is ElmResult.Ok
                }
                if (phase.seconds == 0) {
                    // Self-tests: readiness + every stored monitor result.
                    if (linkNow == null) return@launch fail("The car isn't answering. Ignition ON, then try again.")
                    readiness = DiagnosticsReader(elm, protocol, headersOn).read().readiness.values
                        .maxByOrNull { r -> r.monitors.count { it.available } }
                    selfTests = SelfTestReader(elm, protocol, headersOn, engine?.responseId).readAll { s -> _state.update { it.copy(live = s) } }
                } else {
                    val live = if (phase.pids.isNotEmpty() && linkNow != null)
                        LiveDataSession(elm, protocol, headersOn, engine?.requestId).also { it.begin() } else null
                    val start = System.currentTimeMillis()
                    while (isActive && !elm.closed.isCompleted) {
                        val elapsed = (System.currentTimeMillis() - start) / 1000
                        if (elapsed >= phase.seconds) break
                        if (phase.voltage) {
                            (elm.execute("ATRV", timeoutMs = 600, retries = 0) as? ElmResult.Ok)?.lines?.firstOrNull()
                                ?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()?.let { d.volts += it }
                        }
                        if (live != null) {
                            val s = live.sample(phase.pids)
                            s.values.forEach { (pid, v) -> v.value?.let { d.values.getOrPut(pid) { mutableListOf() } += it } }
                            if ((s.values[0x0D]?.value ?: 0.0) > 5) {
                                live.end()
                                return@launch fail("Test stopped: the car is moving. These tests are for a parked car.")
                            }
                        }
                        _state.update {
                            it.copy(secondsLeft = (phase.seconds - elapsed).toInt(), live = listOfNotNull(
                                d.volts.lastOrNull()?.let { v -> "%.2f V".format(v) },
                                d.values[0x0C]?.lastOrNull()?.let { r -> "${r.toInt()} rpm" },
                                d.values[0x05]?.lastOrNull()?.let { c -> "${c.toInt()} °C" },
                                d.values[0x06]?.lastOrNull()?.let { s -> "STFT %.1f %%".format(s) },
                                d.values[0x2E]?.lastOrNull()?.let { p -> "purge ${p.toInt()} %" },
                            ).joinToString(" · "))
                        }
                    }
                    live?.end()
                }
                data[phase.key] = d
                _state.update { it.copy(doneSteps = i + 1, measuring = false) }
            }
            val verdict = when (kind) {
                TestKind.Battery -> battery(data, ignitionOnAtRest)
                TestKind.FuelTrim -> fuel(data)
                TestKind.Evap -> evap(data, SelfTestReader(elm, protocol, headersOn, engine?.responseId))
                TestKind.SelfTests -> selfTestVerdict(selfTests, readiness, data["cat"])
            }
            log.add(Direction.INFO, "Guided test: ${kind.title}: ${verdict.headline}")
            _state.update { it.copy(verdict = verdict, selfTests = selfTests, readiness = readiness, live = null) }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(waitingForUser = false, measuring = false, message = message) }
    }

    private fun battery(data: Map<String, PhaseData>, ignitionOnAtRest: Boolean): TestVerdict {
        return BatteryAnalysis.evaluate(
            resting = data["rest"]?.volts.orEmpty(), restingWithIgnitionOn = ignitionOnAtRest,
            cranking = data["crank"]?.volts.orEmpty(), idle = data["idle"]?.volts.orEmpty(),
            loaded = data["load"]?.volts.orEmpty(), loadedRpm = data["load"]?.avg(0x0C),
        )
    }

    private fun fuel(data: Map<String, PhaseData>): TestVerdict {
        fun phase(d: PhaseData?) = FuelTrimAnalysis.Phase(
            d?.values?.get(0x06).orEmpty(), d?.values?.get(0x07).orEmpty(), d?.values?.get(0x0C).orEmpty(),
            d?.avg(0x05), d?.values?.get(0x10).orEmpty(),
        )
        return FuelTrimAnalysis.evaluate(phase(data["idle"]), phase(data["high"]))
    }

    private suspend fun evap(data: Map<String, PhaseData>, reader: SelfTestReader): TestVerdict {
        val d = data["idle"] ?: PhaseData()
        val n = listOf(0x06, 0x07, 0x2E).maxOf { d.values[it]?.size ?: 0 }
        val samples = (0 until n).map { i ->
            EvapAnalysis.Sample(d.values[0x2E]?.getOrNull(i), d.values[0x06]?.getOrNull(i), d.values[0x07]?.getOrNull(i))
        }
        val purgeMonitor = runCatching { reader.readAll().firstOrNull { it.mid == 0x3D }?.tests.orEmpty() }.getOrDefault(emptyList())
        return EvapAnalysis.evaluate(samples, purgeMonitor, d.avg(0x05))
    }

    private fun selfTestVerdict(results: List<MonitorResults>, readiness: Readiness?, cat: PhaseData?): TestVerdict {
        val f = mutableListOf<DiagFinding>()
        val failed = results.filter { it.status == "FAILED" }
        val notRun = results.filter { it.status == "Not run yet" }
        f += when {
            results.isEmpty() -> DiagFinding("Stored self-tests", Finding.Info, "The engine computer didn't report any self-test results.")
            failed.isNotEmpty() -> DiagFinding("Self-tests failed", Finding.Watch, failed.joinToString { it.name } + ": outside the car's own limits.")
            else -> DiagFinding("Stored self-tests", Finding.Good,
                "${results.size - notRun.size} passed" + if (notRun.isNotEmpty()) ", ${notRun.size} not run yet." else ".")
        }
        val catResult = results.firstOrNull { it.mid == 0x21 }
        f += when (catResult?.status) {
            "Passed" -> DiagFinding("Catalytic converter self-test", Finding.Good, "Last result: passed.")
            "FAILED" -> DiagFinding("Catalytic converter self-test", Finding.Watch, "Last result: FAILED. Expect a P0420 code if it repeats.")
            "Not run yet" -> DiagFinding("Catalytic converter self-test", Finding.Info, "Not run yet since the codes were cleared.")
            else -> DiagFinding("Catalytic converter self-test", Finding.Info, "Not reported.")
        }
        cat?.values?.get(0x15)?.let { o2 ->
            val history = mapOf(0x15 to o2, 0x0C to cat.values[0x0C].orEmpty(), 0x0D to listOf(0.0), 0x05 to cat.values[0x05].orEmpty())
            LiveChecks.evaluate(history).firstOrNull { it.title == "Downstream O2 sensor" }?.let { c ->
                f += DiagFinding("Downstream O2 at idle", when (c.result) {
                    com.crvdiag.obd.CheckResult.Good -> Finding.Good
                    com.crvdiag.obd.CheckResult.Watch -> Finding.Watch
                    com.crvdiag.obd.CheckResult.Waiting -> Finding.Info
                }, c.detail)
            }
        }
        readiness?.incomplete?.forEach { m ->
            f += DiagFinding("To finish: ${m.name}", Finding.Info, Mode06Catalog.driveCycleTip(m.name))
        }
        val headline = when {
            failed.isNotEmpty() -> "Some self-tests failed"
            f.any { it.level == Finding.Watch } -> "Something needs attention"
            readiness?.incomplete?.isNotEmpty() == true -> "${readiness.incomplete.size} self-test(s) still to finish"
            else -> "Self-tests look good"
        }
        val pending = failed.isEmpty() && f.none { it.level == Finding.Watch } && readiness?.incomplete?.isNotEmpty() == true
        return TestVerdict(headline, f, pending)
    }
}
