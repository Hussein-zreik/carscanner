package com.crvdiag.app.codes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.report.ReportPreview
import com.crvdiag.app.report.ReportType
import com.crvdiag.app.ui.EcuNames
import com.crvdiag.app.ui.shareFile
import com.crvdiag.data.OdometerEstimate
import com.crvdiag.app.ui.components.BigValue
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color
import com.crvdiag.honda.HondaDtcText
import com.crvdiag.obd.DiagnosticsReport
import com.crvdiag.obd.DtcText
import com.crvdiag.obd.EcuInfo
import com.crvdiag.obd.Readiness

/** The unit with the most self-tests (the engine side on this car). */
fun mainReadiness(report: DiagnosticsReport): Readiness? =
    report.readiness.values.maxByOrNull { r -> r.monitors.count { it.available } }

@Composable
fun CodesScreen(
    connection: ConnectionViewModel,
    codes: CodesViewModel,
    odometer: OdometerEstimate?,
    onOpenConnection: () -> Unit,
) {
    val state by codes.state.collectAsState()
    val report by codes.report.collectAsState()
    val context = LocalContext.current
    var showReportDialog by remember { mutableStateOf(false) }
    val carLink by connection.carLink.collectAsState()
    val ready = carLink as? CarLinkState.Ready
    val busy = state is CodesState.Busy || report is ReportState.Building
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Trouble codes", "What the car's computers have noticed. Reading never changes anything.") {
            OutlinedButton(onClick = { showReportDialog = true }, enabled = ready != null && !busy) { Text("Create report…") }
            Button(onClick = { codes.read(connection.elmOrNull(), carLink, connection.log) }, enabled = ready != null && !busy) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state is CodesState.Done) "Read again" else "Read codes")
            }
            OutlinedButton(
                onClick = { showClearDialog = true },
                enabled = ready != null && !busy && state is CodesState.Done,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear codes…") }
        }

        if (ready == null) {
            SectionCard("Not connected", icon = Icons.Filled.Info) {
                MutedText("Connect to the car first. Engine OFF with ignition ON is fine for reading codes.")
                Button(onClick = onOpenConnection) { Text("Go to Connection") }
            }
        }

        when (val r = report) {
            ReportState.Idle -> Unit
            is ReportState.Building -> SectionCard("Creating report", Modifier.fillMaxWidth(), icon = Icons.Filled.Info) {
                MutedText("Reading every scanned system (read-only)… ${r.step}")
            }
            is ReportState.Error -> SectionCard("Report not created", Modifier.fillMaxWidth(), icon = Icons.Filled.Warning, iconTint = Status.Fault.color()) {
                Text(r.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = codes::dismissReport) { Text("Dismiss") }
            }
            is ReportState.Done -> SectionCard("${r.type.label} saved", Modifier.fillMaxWidth(), icon = Icons.Filled.CheckCircle, iconTint = Status.Ok.color()) {
                MutedText("Saved as ${r.file.name}. Find it any time under Logs & reports.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { shareFile(context, r.file, "application/pdf") }) { Text("Share PDF") }
                    TextButton(onClick = codes::dismissReport) { Text("Close") }
                }
                ReportPreview(r.blocks, Modifier.fillMaxWidth())
            }
        }

        when (val s = state) {
            CodesState.Idle -> if (ready != null) MutedText("Tap Read codes.")
            is CodesState.Busy -> MutedText("Reading… ${s.step}")
            is CodesState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
            is CodesState.Done -> {
                s.clearMessage?.let { SectionCard("Clear codes result", icon = Icons.Filled.Info) { Text(it) } }
                ReportView(s.report, ready?.ecus.orEmpty())
            }
        }
    }

    if (showReportDialog) {
        ReportDialog(
            onCreate = { type, notes ->
                showReportDialog = false
                codes.createReport(connection.elmOrNull(), carLink, connection.log, type, notes, odometer)
            },
            onDismiss = { showReportDialog = false },
        )
    }

    if (showClearDialog) {
        ClearCodesDialog(
            onConfirm = {
                showClearDialog = false
                codes.clear(connection.elmOrNull(), carLink, connection.log)
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

private fun describe(code: String) = HondaDtcText.describe(code) ?: DtcText.describe(code)

@Composable
private fun ReportView(report: DiagnosticsReport, ecus: List<EcuInfo>) {
    val mil = report.milOn
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val milStatus = when (mil) { true -> Status.Fault; false -> Status.Ok; null -> Status.Warning }
            SectionCard(
                "Check-engine light", Modifier.fillMaxWidth(),
                icon = if (mil == false) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                iconTint = milStatus.color(),
            ) {
                BigValue(when (mil) { true -> "ON"; false -> "OFF"; null -> "Unknown" }, milStatus)
                MutedText(
                    when (mil) {
                        true -> "The car has confirmed a fault. See the stored codes below."
                        false -> "No confirmed fault is turning the light on."
                        null -> "The car didn't answer the status request, so the light's state is unknown. Tap Read again."
                    },
                )
                if (report.milWithoutStoredCode) {
                    Text(
                        "The light is ON but no stored code was received. The read was incomplete or the code is only " +
                            "visible to a Honda-capable tool. Tap Read again; if it stays empty, compare with Car Scanner.",
                        color = Status.Warning.color(), fontWeight = FontWeight.SemiBold,
                    )
                }
                report.problems.forEach {
                    Text("Some data could not be read: $it", color = Status.Warning.color(), style = MaterialTheme.typography.bodySmall)
                }
            }
            CodeList(
                "Stored codes", "Confirmed faults. These turn the check-engine light on.",
                report.stored, ecus, Status.Fault, "No stored codes", read = report.storedRead,
            )
            CodeList(
                "Pending codes", "Seen once, not confirmed yet. They clear themselves if the problem doesn't return.",
                report.pending, ecus, Status.Warning, "No pending codes", read = report.pendingRead,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            mainReadiness(report)?.let { SelfTests(it) }
            report.freezeFrames.forEach { (ecu, frame) ->
                SectionCard("Snapshot when ${frame.dtc} was set", Modifier.fillMaxWidth(), icon = Icons.Filled.Info) {
                    MutedText("Freeze frame from the ${EcuNames.label(ecu, ecus).lowercase()}.")
                    frame.values.forEach { v ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(v.name, Modifier.weight(1f))
                            Text(v.display(), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeList(
    title: String,
    explanation: String,
    codes: Map<String, List<String>>,
    ecus: List<EcuInfo>,
    status: Status,
    empty: String,
    read: Boolean = true,
) {
    val all = codes.flatMap { (ecu, list) -> list.map { ecu to it } }
    val iconStatus = when {
        !read -> Status.Warning
        all.isEmpty() -> Status.Ok
        else -> status
    }
    SectionCard(title, Modifier.fillMaxWidth(), icon = if (iconStatus == Status.Ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        iconTint = iconStatus.color()) {
        MutedText(explanation)
        if (!read) Text("Couldn't read: the car didn't answer. This does NOT mean there are no codes. Tap Read again.",
            color = Status.Warning.color(), fontWeight = FontWeight.SemiBold)
        else if (all.isEmpty()) Text(empty, color = Status.Ok.color())
        all.forEach { (ecu, code) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.small, color = status.color().copy(alpha = 0.15f)) {
                    Text(
                        code, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = status.color(),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(describe(code), style = MaterialTheme.typography.bodyLarge)
                    MutedText(EcuNames.label(ecu, ecus))
                }
            }
        }
    }
}

@Composable
private fun SelfTests(r: Readiness) {
    val available = r.monitors.filter { it.available }
    val done = available.count { it.complete }
    val status = if (done == available.size) Status.Ok else Status.Warning
    SectionCard("Emission self-tests", Modifier.fillMaxWidth(), icon = Icons.Filled.CheckCircle, iconTint = status.color()) {
        BigValue("$done of ${available.size} done", status)
        MutedText(
            "The car tests its own emission systems while you drive. After codes are cleared or the battery " +
                "is disconnected, these start over.",
        )
        available.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (m.complete) Icons.Filled.CheckCircle else Icons.Filled.Info, null,
                    tint = (if (m.complete) Status.Ok else Status.Warning).color(), modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(m.name, Modifier.weight(1f))
                MutedText(if (m.complete) "Done" else "Not yet")
            }
        }
        if (r.incomplete.isNotEmpty()) {
            MutedText("Tip: mix a cold start, 10+ minutes of steady highway driving and some stop-and-go over a few days.")
        }
    }
}

@Composable
private fun ReportDialog(onCreate: (ReportType, String?) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(ReportType.HealthCheck) }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Info, null) },
        title = { Text("Create a scan report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reads every system this adapter can reach (read-only) and saves a dated PDF.")
                ReportType.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Column {
                            Text(t.label)
                            MutedText(t.purpose)
                        }
                    }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional): shop, repair done…") }, modifier = Modifier.fillMaxWidth())
                MutedText("Airbag, ABS and tire-pressure systems will be listed as NOT SCANNED with this adapter.")
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(type, notes.ifBlank { null }) }) { Text("Create report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** CLAUDE.md rule 4: explicit confirmation, engine OFF / ignition ON (also re-checked by the app). */
@Composable
private fun ClearCodesDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var engineOff by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null, tint = Status.Warning.color()) },
        title = { Text("Clear trouble codes?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Clearing does not repair anything. It erases:")
                Text("• Stored and pending codes, and the snapshot (freeze frame)")
                Text("• All emission self-test results: the car needs several days of driving to redo them, and an emissions inspection may reject it until then")
                Text("If the fault is still there, the code and the check-engine light will come back. Write the codes down first.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = engineOff, onCheckedChange = { engineOff = it })
                    Text("The engine is OFF and the ignition is ON")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm, enabled = engineOff,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear codes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
