package com.crvdiag.app.codes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crvdiag.app.console.CarLinkState
import com.crvdiag.app.console.ConsoleViewModel
import com.crvdiag.honda.HondaDtcText
import com.crvdiag.obd.DiagnosticsReport
import com.crvdiag.obd.DtcText
import com.crvdiag.obd.Readiness

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodesScreen(
    connection: ConsoleViewModel,
    onOpenConsole: () -> Unit,
    onBack: () -> Unit,
    vm: CodesViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val carLink by connection.carLink.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    val connected = carLink is CarLinkState.Ready
    val busy = state is CodesState.Busy

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trouble codes & readiness") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!connected) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Not connected to the car", style = MaterialTheme.typography.titleMedium)
                        Text("Connect in the adapter console first (engine OFF, ignition ON is fine for reading).")
                        Button(onClick = onOpenConsole) { Text("Open adapter console") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.read(connection.elmOrNull(), carLink, connection.log) },
                    enabled = connected && !busy,
                ) { Text("Read codes") }
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    enabled = connected && !busy && state is CodesState.Done,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear codes…") }
            }

            when (val s = state) {
                CodesState.Idle -> Text("Tap Read codes. Reading never changes anything in the car.")
                is CodesState.Busy -> Text("Working… ${s.step}")
                is CodesState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is CodesState.Done -> {
                    s.clearMessage?.let { Text(it, fontWeight = FontWeight.Bold) }
                    ReportView(s.report)
                }
            }
        }
    }

    if (showClearDialog) {
        ClearCodesDialog(
            onConfirm = {
                showClearDialog = false
                vm.clear(connection.elmOrNull(), carLink, connection.log)
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

private fun describe(code: String) = HondaDtcText.describe(code) ?: DtcText.describe(code)

@Composable
private fun ReportView(report: DiagnosticsReport) {
    val mil = report.readiness.values.any { it.milOn }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (mil) "Check-engine light: ON" else "Check-engine light: OFF",
                style = MaterialTheme.typography.titleMedium,
                color = if (mil) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            report.problems.forEach { Text("⚠ $it (data may be incomplete)", color = MaterialTheme.colorScheme.tertiary) }
        }
    }
    CodeSection("Stored (confirmed) codes", report.stored, "No stored codes")
    CodeSection("Pending codes (seen once, not confirmed)", report.pending, "No pending codes")
    report.readiness.forEach { (ecu, r) -> ReadinessCard(ecu, r) }
    report.freezeFrames.forEach { (ecu, frame) ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Freeze frame · $ecu · set by ${frame.dtc}", style = MaterialTheme.typography.titleMedium)
                frame.values.forEach { Text("${it.name}: ${it.display()}") }
            }
        }
    }
}

@Composable
private fun CodeSection(title: String, codes: Map<String, List<String>>, empty: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val all = codes.flatMap { (ecu, list) -> list.map { ecu to it } }
            if (all.isEmpty()) Text(empty)
            all.forEach { (ecu, code) ->
                Text("$code · ${describe(code)}", fontWeight = FontWeight.Bold)
                Text("from $ecu", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReadinessCard(ecu: String, r: Readiness) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Readiness self-tests · $ecu", style = MaterialTheme.typography.titleMedium)
            Text("Codes reported by this unit: ${r.dtcCount}", style = MaterialTheme.typography.bodySmall)
            r.monitors.filter { it.available }.forEach { m ->
                Text(
                    "${if (m.complete) "✅" else "⏳"} ${m.name}: ${if (m.complete) "complete" else "not complete yet"}",
                    color = if (m.complete) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
                )
            }
            if (r.incomplete.isNotEmpty()) {
                Text(
                    "Incomplete tests finish with normal driving (cold start, steady highway, stop-and-go) over a few days.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** CLAUDE.md rule 4: explicit confirmation, engine OFF / ignition ON. */
@Composable
private fun ClearCodesDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var engineOff by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear trouble codes?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This erases stored and pending codes and the freeze frame, and resets ALL readiness self-tests.")
                Text("• The car needs several days of driving to finish the self-tests again; an emissions inspection may reject it until then.")
                Text("• The check-engine light turns off, but comes back if the fault is still there.")
                Text("• Clearing does not repair anything. Write the codes down first.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = engineOff, onCheckedChange = { engineOff = it })
                    Text("Engine is OFF and ignition is ON")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = engineOff,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear codes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
