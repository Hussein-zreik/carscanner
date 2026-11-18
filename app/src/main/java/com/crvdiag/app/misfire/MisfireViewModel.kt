package com.crvdiag.app.misfire

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.data.CrvDatabase
import com.crvdiag.data.DriveLogEntity
import com.crvdiag.elm.Elm327
import com.crvdiag.obd.LiveDataSession
import com.crvdiag.obd.MisfireAnalysis
import com.crvdiag.obd.MisfireReader
import com.crvdiag.obd.MisfireSnapshot
import com.crvdiag.obd.MisfireVerdict
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.TrafficLog.Direction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class IdleTestState(
    val running: Boolean = false,
    val secondsLeft: Int = 0,
    val totalSeconds: Int = IDLE_TEST_SECONDS,
    val rpm: Double? = null,
    val coolant: Double? = null,
    /** New misfires per cylinder so far (null = not reported). */
    val newMisfires: Map<Int, Int?> = emptyMap(),
    val verdict: MisfireVerdict? = null,
    val message: String? = null,
) {
    companion object {
        const val IDLE_TEST_SECONDS = 120
    }
}

/** Guided parked idle test + the drive-watch result of the latest recorded drive. */
class MisfireViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(IdleTestState())
    val state: StateFlow<IdleTestState> = _state.asStateFlow()

    private val _lastDrive = MutableStateFlow<DriveLogEntity?>(null)
    /** Latest recorded drive that has a drive-watch misfire result. */
    val lastDrive: StateFlow<DriveLogEntity?> = _lastDrive.asStateFlow()

    private var job: Job? = null
    private var observing = false

    fun observeDrives() {
        if (observing) return
        observing = true
        viewModelScope.launch {
            CrvDatabase.get(getApplication()).driveLogDao().observeAll().collect { logs ->
                _lastDrive.value = logs.firstOrNull { it.misfireSummary != null }
            }
        }
    }

    /** Screenshot tests only. */
    internal fun showPreview(s: IdleTestState, drive: DriveLogEntity?) {
        observing = true
        _state.value = s
        _lastDrive.value = drive
    }

    fun start(elm: Elm327?, link: CarLinkState, log: TrafficLog, misfireCodes: List<String>, recordingActive: Boolean) {
        if (job?.isActive == true) return
        val ready = link as? CarLinkState.Ready
        if (elm == null || ready == null) {
            _state.value = IdleTestState(message = "Connect to the car first.")
            return
        }
        if (recordingActive) {
            _state.value = IdleTestState(message = "A drive is being recorded. Stop the recording first.")
            return
        }
        val protocol = ready.report.detected?.protocol
        val engine = ready.ecus.firstOrNull { 0x06 in it.supportedPids && 0x10 in it.supportedPids } ?: ready.ecus.firstOrNull()
        val reader = MisfireReader(elm, protocol, ready.report.headersOn, engine?.responseId)
        val live = LiveDataSession(elm, protocol, ready.report.headersOn, engine?.requestId)
        val total = IdleTestState.IDLE_TEST_SECONDS

        job = viewModelScope.launch {
            _state.value = IdleTestState(running = true, secondsLeft = total)
            log.add(Direction.INFO, "Misfire idle test: started")
            val rpm = mutableListOf<Double>()
            val shortTrim = mutableListOf<Double>()
            var longTrim: Double? = null
            var coolant: Double? = null
            try {
                val before: MisfireSnapshot = reader.read()
                live.begin()
                val start = System.currentTimeMillis()
                var lastCounterRead = start
                while (isActive && !elm.closed.isCompleted) {
                    val elapsed = (System.currentTimeMillis() - start) / 1000
                    if (elapsed >= total) break
                    val s = live.sample(listOf(0x0C, 0x0D, 0x05, 0x06, 0x07))
                    val speed = s.values[0x0D]?.value
                    if (speed != null && speed > 5) {
                        _state.update { it.copy(running = false, message = "Test stopped: the car is moving. The idle test is for a parked car.") }
                        return@launch
                    }
                    s.values[0x0C]?.value?.let { if (it > 300) rpm += it }
                    s.values[0x06]?.value?.let { shortTrim += it }
                    s.values[0x07]?.value?.let { longTrim = it }
                    s.values[0x05]?.value?.let { coolant = it }
                    if (System.currentTimeMillis() - lastCounterRead >= 10_000) {
                        lastCounterRead = System.currentTimeMillis()
                        val now = reader.read()
                        _state.update { it.copy(newMisfires = MisfireAnalysis.newMisfires(before, now)) }
                    }
                    _state.update { it.copy(secondsLeft = (total - elapsed).toInt(), rpm = s.values[0x0C]?.value, coolant = coolant) }
                }
                live.end()
                if (rpm.isEmpty()) {
                    _state.update { it.copy(running = false, message = "No engine RPM received. Start the engine (parked), then run the test.") }
                    return@launch
                }
                val after = reader.read()
                val verdict = MisfireAnalysis.evaluate(before, after, rpm, shortTrim, longTrim, coolant, misfireCodes)
                log.add(Direction.INFO, "Misfire idle test: ${verdict.headline}; new=${verdict.newMisfires}")
                _state.update { it.copy(running = false, secondsLeft = 0, newMisfires = verdict.newMisfires, verdict = verdict) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false, message = "Test stopped.") }
    }
}
