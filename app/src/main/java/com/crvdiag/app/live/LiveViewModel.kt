package com.crvdiag.app.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.elm.Elm327
import com.crvdiag.obd.LiveCheck
import com.crvdiag.obd.LiveChecks
import com.crvdiag.obd.LiveDataSession
import com.crvdiag.obd.PidValue
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.TrafficLog.Direction
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LiveUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val values: Map<Int, PidValue> = emptyMap(),
    /** Recent values per PID in base units, oldest first (for sparklines and checks). */
    val history: Map<Int, List<Double>> = emptyMap(),
    val updatesPerSecond: Double = 0.0,
    val multiPid: Boolean = true,
    val checks: List<LiveCheck> = emptyList(),
    val message: String? = null,
) {
    /** CLAUDE.md rule 5: never a screen to watch while driving. */
    val moving: Boolean get() = (values[0x0D]?.value ?: 0.0) > MOVING_KMH

    companion object {
        const val MOVING_KMH = 5.0
        const val HISTORY = 120
    }
}

class LiveViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private val _units = MutableStateFlow(UnitPrefs.load(application))
    val units: StateFlow<UnitPrefs> = _units.asStateFlow()

    private var job: Job? = null

    fun setUnits(u: UnitPrefs) {
        _units.value = u
        UnitPrefs.save(getApplication(), u)
    }

    fun start(elm: Elm327?, link: CarLinkState, log: TrafficLog) {
        if (job?.isActive == true) return
        val ready = link as? CarLinkState.Ready ?: return
        if (elm == null) return
        // The engine computer is the one reporting fuel trims + MAF (see EcuNames).
        val engine = ready.ecus.firstOrNull { 0x06 in it.supportedPids && 0x10 in it.supportedPids } ?: ready.ecus.firstOrNull()
        val session = LiveDataSession(elm, ready.report.detected?.protocol, ready.report.headersOn, engine?.requestId)
        _state.update { it.copy(starting = true, message = null) }

        job = viewModelScope.launch {
            try {
                if (!session.begin()) {
                    _state.update { it.copy(starting = false, message = "The adapter refused to address the engine computer.") }
                    return@launch
                }
                val supported = session.supportedPids().ifEmpty { engine?.supportedPids.orEmpty() }
                val pids = LiveDataSession.DEFAULT_PIDS.filter { it in supported }
                log.add(Direction.INFO, "Live: polling ${engine?.requestId ?: "all units"} PIDs " +
                    pids.joinToString(",") { "%02X".format(it) })
                _state.update { it.copy(running = true, starting = false) }

                val times = ArrayDeque<Long>()
                var emptyInARow = 0
                while (isActive) {
                    val sample = session.sample(pids)
                    times.addLast(sample.timeMs)
                    if (times.size > 10) times.removeFirst()
                    emptyInARow = if (sample.values.isEmpty()) emptyInARow + 1 else 0
                    _state.update { s ->
                        val history = s.history.toMutableMap()
                        sample.values.forEach { (pid, v) ->
                            v.value?.let { x -> history[pid] = (history[pid].orEmpty() + x).takeLast(LiveUiState.HISTORY) }
                        }
                        val span = (times.last() - times.first()).coerceAtLeast(1)
                        s.copy(
                            values = s.values + sample.values,
                            history = history,
                            updatesPerSecond = if (times.size > 1) (times.size - 1) * 1000.0 / span else 0.0,
                            multiPid = session.multiPidWorks,
                            checks = LiveChecks.evaluate(history),
                            message = if (emptyInARow >= 5) "The car stopped answering. Ignition still ON?" else null,
                        )
                    }
                }
            } finally {
                withContext(NonCancellable) { session.end() }
                log.add(Direction.INFO, "Live: stopped")
                _state.update { it.copy(running = false, starting = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Screenshot tests only. */
    internal fun showPreviewState(s: LiveUiState) {
        _state.value = s
    }
}
