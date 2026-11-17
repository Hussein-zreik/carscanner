package com.crvdiag.app.codes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.report.Block
import com.crvdiag.app.report.PdfReportWriter
import com.crvdiag.app.report.ReportFormatter
import com.crvdiag.app.report.ReportInput
import com.crvdiag.app.report.ReportType
import com.crvdiag.data.CrvDatabase
import com.crvdiag.data.OdometerEstimate
import com.crvdiag.data.ScanReportEntity
import com.crvdiag.elm.Elm327
import com.crvdiag.honda.HondaDtcText
import com.crvdiag.obd.ClearOutcome
import com.crvdiag.obd.CodeClearer
import com.crvdiag.obd.DiagnosticsReader
import com.crvdiag.obd.DiagnosticsReport
import com.crvdiag.obd.DtcText
import com.crvdiag.obd.ScanReportCollector
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.TrafficLog.Direction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed interface CodesState {
    data object Idle : CodesState
    data class Busy(val step: String) : CodesState
    data class Done(val report: DiagnosticsReport, val clearMessage: String? = null) : CodesState
    data class Error(val message: String) : CodesState
}

sealed interface ReportState {
    data object Idle : ReportState
    data class Building(val step: String) : ReportState
    data class Done(val type: ReportType, val blocks: List<Block>, val file: File) : ReportState
    data class Error(val message: String) : ReportState
}

class CodesViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<CodesState>(CodesState.Idle)
    val state: StateFlow<CodesState> = _state.asStateFlow()

    private val _report = MutableStateFlow<ReportState>(ReportState.Idle)
    val report: StateFlow<ReportState> = _report.asStateFlow()

    private val busy get() = _state.value is CodesState.Busy || _report.value is ReportState.Building

    /** Screenshot tests only: show a given state without a car. */
    internal fun showPreviewState(state: CodesState, report: ReportState = ReportState.Idle) {
        _state.value = state
        _report.value = report
    }

    fun read(elm: Elm327?, link: CarLinkState, log: TrafficLog, clearMessage: String? = null) {
        if (busy) return
        val ready = link as? CarLinkState.Ready
        if (elm == null || ready == null) {
            _state.value = CodesState.Error("Not connected. Open Connection and connect first.")
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

    /** Read-only full scan → PDF report saved in the app, listed under Logs & reports. */
    fun createReport(
        elm: Elm327?,
        link: CarLinkState,
        log: TrafficLog,
        type: ReportType,
        notes: String?,
        odometer: OdometerEstimate?,
    ) {
        if (busy) return
        val ready = link as? CarLinkState.Ready
        if (elm == null || ready == null) {
            _report.value = ReportState.Error("Not connected.")
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                log.add(Direction.INFO, "Report: ${type.label} started")
                val protocol = ready.report.detected?.protocol
                val data = ScanReportCollector(elm, protocol, ready.report.headersOn).collect { _report.value = ReportState.Building(it) }
                _state.value = CodesState.Done(data.diagnostics)
                val now = ZonedDateTime.now()
                val input = ReportInput(
                    type = type, notes = notes, createdAt = now,
                    appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?",
                    adapterId = ready.report.adapterId, protocolLabel = protocol?.label, voltage = ready.report.voltage,
                    odometerMiles = odometer?.miles, odometerEstimated = odometer?.estimated == true,
                    ecus = ready.ecus, data = data,
                    describeCode = { HondaDtcText.describe(it) ?: DtcText.describe(it) },
                )
                val blocks = ReportFormatter.build(input)
                val file = File(app.filesDir, "reports/report_${type.name}_${STAMP.format(now)}.pdf")
                withContext(Dispatchers.IO) {
                    PdfReportWriter.write(blocks, "CR-V Diag · ${type.label} · VIN ${data.vin ?: "not reported"}", file)
                    CrvDatabase.get(app).scanReportDao().insert(
                        ScanReportEntity(createdAtEpochMs = now.toInstant().toEpochMilli(), type = type.label, vin = data.vin,
                            fileName = file.name, summary = ReportFormatter.summary(input)),
                    )
                }
                log.add(Direction.INFO, "Report: saved ${file.name}")
                _report.value = ReportState.Done(type, blocks, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add(Direction.INFO, "Report failed: ${e.message}")
                _report.value = ReportState.Error("Report failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun dismissReport() {
        if (_report.value !is ReportState.Building) _report.value = ReportState.Idle
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
    }
}
