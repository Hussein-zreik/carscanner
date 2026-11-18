package com.crvdiag.app.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.recording.DriveRecorder
import com.crvdiag.app.recording.RecordingService
import com.crvdiag.data.CrvDatabase
import com.crvdiag.data.DriveLogEntity
import com.crvdiag.elm.Elm327
import com.crvdiag.obd.LiveCheck
import com.crvdiag.obd.LiveChecks
import com.crvdiag.obd.LiveDataSession
import com.crvdiag.obd.PidValue
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.TrafficLog.Direction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RecordingInfo(val startedAtMs: Long, val rows: Int)

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
    /** Non-null while a drive is being recorded. */
    val recording: RecordingInfo? = null,
    val recordingRequested: Boolean = false,
) {
    /** CLAUDE.md rule 5: never a screen to watch while driving. */
    val moving: Boolean get() = (values[0x0D]?.value ?: 0.0) > MOVING_KMH

    companion object {
        const val MOVING_KMH = 5.0
        const val HISTORY = 120
    }
}

/**
 * Polls the engine computer while the Live screen is open OR a drive is being recorded.
 * Recording writes every polling cycle to CSV + the raw adapter log and keeps running in the
 * background (RecordingService) until stopped or the link drops.
 */
class LiveViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private val _units = MutableStateFlow(UnitPrefs.load(application))
    val units: StateFlow<UnitPrefs> = _units.asStateFlow()

    private val dao by lazy { CrvDatabase.get(application).driveLogDao() }
    private val logsDir = File(application.filesDir, "logs")

    private var job: Job? = null
    private var screenVisible = false

    // Recording state (touched from the polling coroutine and the main thread).
    @Volatile private var recorder: DriveRecorder? = null
    @Volatile private var recordId: Long? = null
    @Volatile private var trafficLog: TrafficLog? = null
    private val rawListener: (String) -> Unit = { line -> recorder?.rawLine(line) }

    fun setUnits(u: UnitPrefs) {
        _units.value = u
        UnitPrefs.save(getApplication(), u)
    }

    /** The Live screen appeared or disappeared; polling continues off-screen only while recording. */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (!visible && recorder == null && !_state.value.recordingRequested) stop()
    }

    fun start(elm: Elm327?, link: CarLinkState, log: TrafficLog) {
        if (job?.isActive == true) return
        val ready = link as? CarLinkState.Ready ?: return
        if (elm == null) return
        trafficLog = log
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
                while (isActive && !elm.closed.isCompleted) {
                    if (_state.value.recordingRequested && recorder == null) beginRecording(pids, log)
                    val sample = session.sample(pids)
                    recorder?.append(sample)
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
                            recording = recorder?.let { r -> RecordingInfo(r.startedAtMs, r.rows) },
                        )
                    }
                    // Nothing answered: don't hammer a silent car or a dead link.
                    if (sample.values.isEmpty()) delay(500)
                }
                if (elm.closed.isCompleted) log.add(Direction.INFO, "Live: link closed")
            } finally {
                // Link lost while recording: save this part, keep the service, resume after reconnect.
                val linkLost = elm.closed.isCompleted
                val resume = linkLost && _state.value.recordingRequested
                withContext(NonCancellable) {
                    finishRecording(reason = if (linkLost) "link lost" else "polling stopped", keepService = resume)
                    if (!linkLost) session.end()
                }
                log.add(Direction.INFO, "Live: stopped")
                _state.update { it.copy(running = false, starting = false, recordingRequested = resume) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Starts polling if needed, then records every cycle until [stopRecording]. */
    fun startRecording(elm: Elm327?, link: CarLinkState, log: TrafficLog) {
        if (link !is CarLinkState.Ready || elm == null) return
        _state.update { it.copy(recordingRequested = true, message = null) }
        RecordingService.start(getApplication())
        start(elm, link, log)
    }

    fun stopRecording() {
        _state.update { it.copy(recordingRequested = false) }
        viewModelScope.launch {
            finishRecording(reason = "stopped by user")
            RecordingService.stop(getApplication()) // also when stopped while reconnecting
        }
        if (!screenVisible) stop()
    }

    private var attached = false

    /**
     * Follows the connection so a recording survives a dropped link: when the car link is Ready
     * again the recording continues in a new file; if reconnecting fails it ends cleanly.
     * Runs in viewModelScope, so it also works with the screen off.
     */
    fun attach(connection: com.crvdiag.app.connection.ConnectionViewModel) {
        if (attached) return
        attached = true
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(connection.state, connection.carLink) { l, c -> l to c }.collect { (l, c) ->
                if (!_state.value.recordingRequested || job?.isActive == true) return@collect
                when {
                    c is CarLinkState.Ready -> {
                        connection.log.add(Direction.INFO, "Recording: resuming after reconnect (new file)")
                        start(connection.elmOrNull(), c, connection.log)
                    }
                    l is com.crvdiag.app.connection.LinkState.Failed || l is com.crvdiag.app.connection.LinkState.Disconnected -> {
                        connection.log.add(Direction.INFO, "Recording: stopped, the link could not be restored")
                        _state.update { it.copy(recordingRequested = false, message = "Recording stopped: the link was lost and couldn't be restored. The saved part is in Logs.") }
                        RecordingService.stop(getApplication())
                    }
                }
            }
        }
    }

    private suspend fun beginRecording(pids: List<Int>, log: TrafficLog) {
        val r = DriveRecorder(logsDir, System.currentTimeMillis(), pids)
        recorder = r
        log.addListener(rawListener)
        recordId = withContext(Dispatchers.IO) {
            dao.insert(DriveLogEntity(startedAtEpochMs = r.startedAtMs, csvFile = r.csvFile.name, rawFile = r.rawFile.name))
        }
        log.add(Direction.INFO, "Recording: started ${r.csvFile.name}")
    }

    private suspend fun finishRecording(reason: String, keepService: Boolean = false) {
        val r = recorder ?: return
        recorder = null
        trafficLog?.let {
            it.add(Direction.INFO, "Recording: saved ${r.csvFile.name} (${r.rows} rows, $reason)")
            it.removeListener(rawListener)
        }
        val id = recordId
        recordId = null
        withContext(Dispatchers.IO) {
            r.close()
            id?.let { dao.finish(it, System.currentTimeMillis(), r.rows) }
        }
        if (!keepService) RecordingService.stop(getApplication())
        _state.update {
            it.copy(
                recording = null,
                message = if (keepService) "Link lost: this part is saved (${r.rows} rows). Recording resumes after the reconnect."
                else "Drive log saved (${r.rows} rows). See Logs.",
            )
        }
    }

    override fun onCleared() {
        // App closed while recording: close files and mark the log finished (outside viewModelScope).
        val r = recorder ?: return
        recorder = null
        trafficLog?.removeListener(rawListener)
        r.close()
        val id = recordId
        CoroutineScope(Dispatchers.IO + NonCancellable).launch { id?.let { dao.finish(it, System.currentTimeMillis(), r.rows) } }
        RecordingService.stop(getApplication())
    }

    /** Screenshot tests only. */
    internal fun showPreviewState(s: LiveUiState) {
        _state.value = s
    }
}
