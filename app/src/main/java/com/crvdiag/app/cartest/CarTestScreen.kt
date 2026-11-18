package com.crvdiag.app.cartest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.components.BigValue
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.components.StepRow
import com.crvdiag.app.ui.shareFile
import com.crvdiag.app.ui.theme.Status

private val PLANNED = listOf(
    "Connection", "Car network", "Trouble codes & readiness", "VIN", "Permanent codes & misfire counters", "Live data", "PDF report",
)

private fun StepStatus.ui() = when (this) {
    StepStatus.Pass -> Status.Ok
    StepStatus.Warn -> Status.Warning
    StepStatus.Fail -> Status.Fault
    StepStatus.Skipped -> Status.Neutral
}

@Composable
fun CarTestScreen(vm: CarTestViewModel, onStart: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val results = when (val s = state) {
        is CarTestState.Running -> s.results
        is CarTestState.Done -> s.results
        CarTestState.Idle -> emptyList()
    }
    val running = state is CarTestState.Running

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Car test", "One tap checks everything (read-only) and makes one file to send.") {
            Button(onClick = onStart, enabled = !running) { Text(if (state is CarTestState.Done) "Run again" else "Start car test") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionCard("Before you start", Modifier.weight(1f), icon = Icons.Filled.Info) {
                Text("1.  Park the car. Ignition ON; start the engine if you want real live values.")
                Text("2.  Tablet on the adapter's Wi-Fi; Car Scanner closed (one app at a time).")
                Text("3.  Tap Start car test and wait about a minute. Nothing in the car is changed.")
                MutedText("If the app isn't connected yet, it connects by itself with your last Wi-Fi settings.")
            }
            SectionCard("Checks", Modifier.weight(1f), icon = Icons.Filled.CheckCircle) {
                val current = (state as? CarTestState.Running)?.step
                PLANNED.forEach { title ->
                    val r = results.firstOrNull { it.title == title }
                    val status = when {
                        r != null -> r.status.ui()
                        running && results.size == PLANNED.indexOf(title) || (running && title == "Connection" && results.isEmpty()) -> Status.Busy
                        else -> Status.Neutral
                    }
                    StepRow(title, r?.detail ?: current?.takeIf { status == Status.Busy }, status)
                }
                results.filter { it.title !in PLANNED }.forEach { StepRow(it.title, it.detail, it.status.ui()) }
            }
        }
        (state as? CarTestState.Done)?.let { done ->
            val fails = done.results.count { it.status == StepStatus.Fail }
            val warns = done.results.count { it.status == StepStatus.Warn }
            SectionCard("Result", Modifier.fillMaxWidth(), icon = Icons.Filled.CheckCircle) {
                BigValue(
                    when {
                        fails > 0 -> "$fails check(s) failed"
                        warns > 0 -> "$warns check(s) need attention"
                        else -> "All checks passed"
                    },
                    when { fails > 0 -> Status.Fault; warns > 0 -> Status.Warning; else -> Status.Ok },
                )
                Text("Send this file in the chat: it has every result plus the full adapter log.", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { shareFile(context, done.resultsFile, "text/plain") }) { Text("Share results file") }
                    done.pdf?.let { pdf -> OutlinedButton(onClick = { shareFile(context, pdf, "application/pdf") }) { Text("Share PDF report") } }
                }
                MutedText(done.resultsFile.name)
            }
        }
    }
}
