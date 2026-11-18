package com.crvdiag.app.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.shareFile
import com.crvdiag.data.ScanReportEntity
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.components.StatusPill
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color
import com.crvdiag.data.DriveLogEntity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

@Composable
fun LogsScreen(vm: LogsViewModel) {
    val logs by vm.logs.collectAsState()
    val reports by vm.reports.collectAsState()
    var reportToDelete by remember { mutableStateOf<ScanReportEntity?>(null) }
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<DriveLogEntity?>(null) }
    val captures by vm.captures.collectAsState()
    LaunchedEffect(Unit) {
        vm.observe()
        vm.refreshCaptures()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Logs & reports", "Scan reports and recorded drives. Files stay on this tablet until you share them.")
        SectionCard("Scan reports", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.List) {
            if (reports.isEmpty()) MutedText("No reports yet. Create one from Codes → Create report.")
            reports.forEach { r ->
                val pdf = File(vm.reportsDir, r.fileName)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.type} · " + DATE.format(Instant.ofEpochMilli(r.createdAtEpochMs).atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        MutedText(r.summary)
                    }
                    Button(onClick = { shareFile(context, pdf, "application/pdf") }, enabled = pdf.exists()) { Text("Share PDF") }
                    TextButton(onClick = { reportToDelete = r }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                }
            }
        }
        if (captures.isNotEmpty()) {
            SectionCard("Honda captures (Phase 2)", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.List) {
                captures.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            MutedText("%.1f KB".format(f.length() / 1024.0))
                        }
                        Button(onClick = { shareFile(context, f, "text/plain") }) { Text("Share") }
                        TextButton(onClick = { vm.deleteCapture(f) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                    }
                }
            }
        }
        Text("Drive logs", style = MaterialTheme.typography.titleLarge)
        if (logs.isEmpty()) {
            SectionCard("No drives recorded yet", icon = Icons.Filled.Info) {
                MutedText("Open Live data and tap Record drive. Recording keeps going with the screen off; the live view hides while the car moves.")
            }
        }
        logs.forEach { log ->
            val csv = File(vm.dir, log.csvFile)
            val raw = File(vm.dir, log.rawFile)
            SectionCard(null, Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(DATE.format(Instant.ofEpochMilli(log.startedAtEpochMs).atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        MutedText(
                            listOfNotNull(
                                log.endedAtEpochMs?.let { duration(it - log.startedAtEpochMs) },
                                "${log.rows} rows",
                                "%.1f MB".format((csv.length() + raw.length()) / 1_048_576.0).takeIf { csv.exists() },
                            ).joinToString(" · "),
                        )
                    }
                    if (log.endedAtEpochMs == null) StatusPill("Recording", Status.Fault)
                }
                log.misfireSummary?.let { m ->
                    Text(m, color = if (Regex("cyl \\d: [1-9]").containsMatchIn(m)) Status.Warning.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { shareFile(context, csv, "text/csv") }, enabled = csv.exists()) { Text("Share CSV") }
                    OutlinedButton(onClick = { shareFile(context, raw, "text/plain") }, enabled = raw.exists()) { Text("Share adapter log") }
                    TextButton(
                        onClick = { toDelete = log }, enabled = log.endedAtEpochMs != null,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
            }
        }
        if (logs.isNotEmpty()) {
            SectionCard("About these files", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.List) {
                MutedText("CSV: one row per update, opens in Excel or Google Sheets (temperatures in °C, speed in km/h, pressure in kPa).")
                MutedText("Adapter log: the raw conversation with the car. Sharing it helps improve the app with your car's real data.")
            }
        }
    }

    reportToDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { reportToDelete = null },
            title = { Text("Delete this report?") },
            text = { Text("The PDF is removed from the tablet. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteReport(r); reportToDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { reportToDelete = null }) { Text("Cancel") } },
        )
    }
    toDelete?.let { log ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this drive log?") },
            text = { Text("Both files are removed from the tablet. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(log); toDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%dh %02dm".format(s / 3600, s % 3600 / 60) else "%dm %02ds".format(s / 60, s % 60)
}

