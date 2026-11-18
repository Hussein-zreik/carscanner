package com.crvdiag.app.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.components.BigValue
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.components.StatusPill
import com.crvdiag.app.ui.shareFile
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color

/**
 * Phase 2 capture: records the Honda requests Car Scanner sends, relayed through the tablet.
 * Parked use only (the owner works the iPhone; nothing here is meant to be watched while driving).
 */
@Composable
fun CaptureScreen(vm: CaptureViewModel, onStart: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // Keep the tablet awake while relaying (screen-off would drop the Wi-Fi sockets).
    val view = LocalView.current
    val running = state is CaptureState.Running || state is CaptureState.Starting
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("Honda capture (Phase 2)", "Learn Honda's own data requests from Car Scanner, safely, through the tablet.") {
            TextButton(onClick = onBack, enabled = !running) { Text("Back") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.width(470.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SectionCard("How it works", Modifier.fillMaxWidth(), icon = Icons.Filled.Info) {
                    Steps(
                        "Park, engine idling. Tablet AND iPhone joined to the adapter's Wi-Fi.",
                        "Tap Start capture here. The app's own car link closes (the adapter takes one app at a time).",
                        "In Car Scanner: Settings → adapter → Wi-Fi, enter the tablet address shown here, port 35000, then connect.",
                        "Choose the right car profile (CR-V, 2.4 gasoline, RE4) and open the Honda values: transmission temperature, " +
                            "knock, VTC / cam, idle air control. Watch each for ~1 minute.",
                        "Take screenshots of Car Scanner's values, then tap Stop capture and share the file + screenshots.",
                    )
                }
                SectionCard("Safety", Modifier.fillMaxWidth(), icon = Icons.Filled.Warning, iconTint = Status.Warning.color()) {
                    MutedText(
                        "Only requests that READ data reach the car. Clearing codes, writing, unlocking, resets and tests " +
                            "are blocked, answered \"?\" and written to the file. Don't use Car Scanner's clear / test functions during the capture.",
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (val s = state) {
                    CaptureState.Idle -> SectionCard("Ready", Modifier.fillMaxWidth()) {
                        MutedText("The tablet will relay Car Scanner to the adapter and record every request and answer.")
                        Button(onClick = onStart) { Text("Start capture") }
                    }
                    is CaptureState.Starting -> SectionCard("Starting", Modifier.fillMaxWidth()) {
                        StatusPill(s.step, Status.Busy)
                    }
                    is CaptureState.Running -> {
                        SectionCard("Point Car Scanner at", Modifier.fillMaxWidth()) {
                            BigValue("${s.tabletIp ?: "tablet IP not found"} : ${s.port}", Status.Ok)
                            if (s.tabletIp == null) MutedText("Join the adapter's Wi-Fi on the tablet, then stop and start again.")
                            StatusPill(
                                if (s.stats.clientConnected) "Car Scanner connected" else "Waiting for Car Scanner…",
                                if (s.stats.clientConnected) Status.Ok else Status.Busy,
                            )
                            Text("Relayed: ${s.stats.relayed} · Blocked: ${s.stats.blocked}", style = MaterialTheme.typography.titleMedium)
                            Button(
                                onClick = vm::stop,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text("Stop capture") }
                        }
                        SectionCard("Latest requests", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.List) {
                            if (s.stats.recent.isEmpty()) MutedText("Nothing yet.")
                            s.stats.recent.asReversed().forEach { line ->
                                Text(
                                    line, fontFamily = FontFamily.Monospace,
                                    color = if (line.startsWith("BLOCKED")) Status.Warning.color() else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    is CaptureState.Stopped -> SectionCard("Capture saved", Modifier.fillMaxWidth()) {
                        Text(s.file.name, fontFamily = FontFamily.Monospace)
                        val sum = s.summary
                        Text(
                            "Standard requests: ${sum.standardRequests} · Honda requests: ${sum.honda.size} distinct · Blocked: ${sum.blocked.size}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (sum.honda.isEmpty()) {
                            MutedText("No Honda-specific requests were seen. Check Car Scanner's car profile and open its Honda values, then capture again.")
                        }
                        sum.honda.take(40).forEach { Text("${it.header}  ${it.request}  ×${it.count}", fontFamily = FontFamily.Monospace) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { shareFile(context, s.file, "text/plain") }) { Text("Share file") }
                            OutlinedButton(onClick = vm::reset) { Text("New capture") }
                        }
                        MutedText("Then reconnect normally from the Connection screen.")
                    }
                    is CaptureState.Failed -> SectionCard("Capture stopped", Modifier.fillMaxWidth(), icon = Icons.Filled.Warning, iconTint = Status.Fault.color()) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::reset) { Text("OK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Steps(vararg steps: String) {
    steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyLarge) }
}
