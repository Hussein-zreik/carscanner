package com.crvdiag.app.cartest

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.connection.LinkState
import com.crvdiag.app.connection.SavedLink
import com.crvdiag.app.report.ReportInput
import com.crvdiag.app.report.ReportSaver
import com.crvdiag.app.report.ReportType
import com.crvdiag.data.OdometerEstimate
import com.crvdiag.honda.CarProfile
import com.crvdiag.honda.HondaDtcText
import com.crvdiag.obd.DtcText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed interface CarTestState {
    data object Idle : CarTestState
    data class Running(val step: String, val results: List<StepResult>) : CarTestState
    data class Done(val results: List<StepResult>, val resultsFile: File, val pdf: File?) : CarTestState
}

/** One-tap Car test: connect, run every read-only check, make one file to send. */
class CarTestViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<CarTestState>(CarTestState.Idle)
    val state: StateFlow<CarTestState> = _state.asStateFlow()

    /** Screenshot tests only. */
    internal fun showPreviewState(s: CarTestState) {
        _state.value = s
    }

    fun run(connection: ConnectionViewModel, recordingActive: Boolean, odometer: OdometerEstimate?) {
        if (_state.value is CarTestState.Running) return
        val app = getApplication<Application>()
        val started = ZonedDateTime.now()
        val raw = mutableListOf<String>()
        val listener: (String) -> Unit = { line -> synchronized(raw) { raw += line } }
        val results = mutableListOf<StepResult>()
        fun progress(step: String) {
            _state.value = CarTestState.Running(step, results.toList())
        }

        viewModelScope.launch {
            connection.log.addListener(listener)
            var pdf: File? = null
            try {
                progress("Connecting")
                if (recordingActive) {
                    results += StepResult("Connection", StepStatus.Fail, "A drive is being recorded. Stop the recording first.")
                    return@launch
                }
                val ready = awaitReady(connection)
                if (ready == null) {
                    val link = connection.state.value
                    results += StepResult("Connection", StepStatus.Fail, when (link) {
                        is LinkState.Failed -> link.message
                        else -> (connection.carLink.value as? CarLinkState.Failed)?.hint
                            ?: "Couldn't connect. Ignition ON, tablet on the adapter's Wi-Fi, Car Scanner closed. Or connect on the Connection screen first."
                    })
                    return@launch
                }
                val linkLabel = (connection.state.value as? LinkState.Connected)?.label ?: "?"
                results += StepResult("Connection", StepStatus.Pass,
                    "$linkLabel · ${ready.report.adapterId ?: "adapter"} · battery ${ready.report.voltage ?: "?"}")

                val elm = connection.elmOrNull() ?: run {
                    results += StepResult("Car checks", StepStatus.Fail, "The link closed before the checks could start.")
                    return@launch
                }
                val protocol = ready.report.detected?.protocol
                val outcome = CarTestRunner(elm, protocol, ready.report.headersOn, ready.ecus).run { step -> progress(step) }
                results += outcome.results

                progress("PDF report")
                outcome.scan?.let { scan ->
                    val input = ReportInput(
                        type = ReportType.HealthCheck, notes = "Created by the one-tap Car test.", createdAt = ZonedDateTime.now(),
                        appVersion = ReportSaver.appVersion(app), adapterId = ready.report.adapterId, protocolLabel = protocol?.label,
                        voltage = ready.report.voltage, odometerMiles = odometer?.miles, odometerEstimated = odometer?.estimated == true,
                        ecus = ready.ecus, data = scan, describeCode = { HondaDtcText.describe(it) ?: DtcText.describe(it) },
                    )
                    pdf = runCatching { ReportSaver.save(app, input).second }.getOrNull()
                }
                val saved = pdf
                results += if (saved != null && saved.length() > 0) StepResult("PDF report", StepStatus.Pass, "${saved.name} (${saved.length() / 1024} KB)")
                else StepResult("PDF report", StepStatus.Fail, "The PDF could not be created.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results += StepResult("Car test", StepStatus.Fail, "Stopped by an error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                connection.log.removeListener(listener)
                val file = writeResults(app, started, results, synchronized(raw) { raw.toList() })
                _state.value = CarTestState.Done(results.toList(), file, pdf)
            }
        }
    }

    /** Uses the existing link, or connects with the last Wi-Fi settings and waits up to 45 s. */
    private suspend fun awaitReady(connection: ConnectionViewModel): CarLinkState.Ready? {
        (connection.carLink.value as? CarLinkState.Ready)?.let { return it }
        val idle = connection.state.value.let { it is LinkState.Disconnected || it is LinkState.Failed }
        if (idle) {
            // Bluetooth and Android 17+ may need a permission prompt: those connect on the Connection screen.
            if (connection.savedLink.value !is SavedLink.Wifi || Build.VERSION.SDK_INT >= 37) return null
            connection.connectLast()
        }
        return withTimeoutOrNull(45_000) {
            combine(connection.state, connection.carLink) { l, c -> l to c }
                .first { (l, c) -> c is CarLinkState.Ready || c is CarLinkState.Failed || l is LinkState.Failed }
                .second as? CarLinkState.Ready
        }
    }

    private suspend fun writeResults(app: Application, started: ZonedDateTime, results: List<StepResult>, raw: List<String>): File =
        withContext(Dispatchers.IO) {
            val file = File(app.filesDir, "reports/car-test_${STAMP.format(started)}.txt").apply { parentFile?.mkdirs() }
            val counts = results.groupingBy { it.status }.eachCount()
            // Every summary line starts with '#', so the whole file is also a valid testdata/ recording.
            file.bufferedWriter().use { w ->
                w.write("# CR-V Diag car test · ${HUMAN.format(started)} · app ${ReportSaver.appVersion(app)}\n")
                w.write("# Car: ${CarProfile.DESCRIPTION} · profile VIN ${CarProfile.VIN}\n")
                w.write("# RESULTS\n")
                results.forEach { w.write("# [${it.status.name.uppercase()}] ${it.title}: ${it.detail}\n") }
                w.write("# Summary: ${counts[StepStatus.Pass] ?: 0} passed, ${counts[StepStatus.Warn] ?: 0} need attention, " +
                    "${counts[StepStatus.Fail] ?: 0} failed\n")
                w.write("# ---- raw adapter log follows (testdata format) ----\n")
                raw.forEach { w.write(it); w.write("\n") }
            }
            file
        }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val HUMAN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
