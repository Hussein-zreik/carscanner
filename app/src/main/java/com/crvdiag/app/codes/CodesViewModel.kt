package com.crvdiag.app.codes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.elm.Elm327
import com.crvdiag.obd.ClearOutcome
import com.crvdiag.obd.CodeClearer
import com.crvdiag.obd.DiagnosticsReader
import com.crvdiag.obd.DiagnosticsReport
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.TrafficLog.Direction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CodesState {
    data object Idle : CodesState
    data class Busy(val step: String) : CodesState
    data class Done(val report: DiagnosticsReport, val clearMessage: String? = null) : CodesState
    data class Error(val message: String) : CodesState
}

class CodesViewModel : ViewModel() {
    private val _state = MutableStateFlow<CodesState>(CodesState.Idle)
    val state: StateFlow<CodesState> = _state.asStateFlow()

    private val busy get() = _state.value is CodesState.Busy

    /** Screenshot tests only: show a given state without a car. */
    internal fun showPreviewState(state: CodesState) {
        _state.value = state
    }

    fun read(elm: Elm327?, link: CarLinkState, log: TrafficLog, clearMessage: String? = null) {
        if (busy) return
        val ready = link as? CarLinkState.Ready
        if (elm == null || ready == null) {
            _state.value = CodesState.Error("Not connected. Open the adapter console and connect first.")
            return
        }
        viewModelScope.launch {
            val reader = DiagnosticsReader(elm, ready.report.detected?.protocol, ready.report.headersOn)
            val report = reader.read { _state.value = CodesState.Busy(it) }
            log.add(Direction.INFO, "Codes: stored=${report.stored} pending=${report.pending} " +
                "incomplete=${report.readiness.mapValues { r -> r.value.incomplete.map { it.name } }}")
            _state.value = CodesState.Done(report, clearMessage)
        }
    }

    /** Called only after the user confirmed the dialog. [CodeClearer] re-checks engine OFF. */
    fun clear(elm: Elm327?, link: CarLinkState, log: TrafficLog) {
        if (busy) return
        val ready = link as? CarLinkState.Ready
        if (elm == null || ready == null) {
            _state.value = CodesState.Error("Not connected.")
            return
        }
        viewModelScope.launch {
            _state.value = CodesState.Busy("Checking engine is OFF, then clearing")
            log.add(Direction.INFO, "Clear codes: confirmed by user")
            val outcome = CodeClearer(elm, ready.report.detected?.protocol, ready.report.headersOn).clear()
            val message = when (outcome) {
                is ClearOutcome.Cleared -> "Codes cleared by ${outcome.ecus.joinToString()}." +
                    outcome.rejected.entries.joinToString("") { " ${it.key} refused: ${it.value}." }
                is ClearOutcome.Refused -> "Not cleared: ${outcome.reason}"
                is ClearOutcome.Failed -> "Clear failed: ${outcome.reason}"
            }
            log.add(Direction.INFO, "Clear codes: $message")
            _state.value = CodesState.Idle
            read(elm, link, log, clearMessage = message) // show the state after clearing
        }
    }
}
