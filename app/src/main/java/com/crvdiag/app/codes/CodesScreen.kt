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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.ui.EcuNames
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
fun CodesScreen(connection: ConnectionViewModel, codes: CodesViewModel, onOpenConnection: () -> Unit) {
    val state by codes.state.collectAsState()
    val carLink by connection.carLink.collectAsState()
    val ready = carLink as? CarLinkState.Ready
    val busy = state is CodesState.Busy
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Trouble codes", "What the car's computers have noticed. Reading never changes anything.") {
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
    val mil = report.readiness.values.any { it.milOn }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionCard(
                "Check-engine light", Modifier.fillMaxWidth(),
                icon = if (mil) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                iconTint = (if (mil) Status.Fault else Status.Ok).color(),
            ) {
                BigValue(if (mil) "ON" else "OFF", if (mil) Status.Fault else Status.Ok)
                MutedText(
                    if (mil) "The car has confirmed a fault. See the stored codes below."
                    else "No confirmed fault is turning the light on.",
                )
                report.problems.forEach {
                    Text("Some data could not be read: $it", color = Status.Warning.color(), style = MaterialTheme.typography.bodySmall)
                }
            }
            CodeList(
                "Stored codes", "Confirmed faults. These turn the check-engine light on.",
                report.stored, ecus, Status.Fault, "No stored codes",
            )
            CodeList(
                "Pending codes", "Seen once, not confirmed yet. They clear themselves if the problem doesn't return.",
                report.pending, ecus, Status.Warning, "No pending codes",
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
) {
    val all = codes.flatMap { (ecu, list) -> list.map { ecu to it } }
    SectionCard(title, Modifier.fillMaxWidth(), icon = if (all.isEmpty()) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        iconTint = (if (all.isEmpty()) Status.Ok else status).color()) {
        MutedText(explanation)
        if (all.isEmpty()) Text(empty, color = Status.Ok.color())
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
