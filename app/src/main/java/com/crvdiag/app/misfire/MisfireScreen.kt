package com.crvdiag.app.misfire

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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.components.BigValue
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.icons.AppIcons
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color
import com.crvdiag.obd.Finding

@Composable
fun MisfireScreen(vm: MisfireViewModel, connected: Boolean, onStart: () -> Unit, onOpenLive: () -> Unit) {
    val s by vm.state.collectAsState()
    val drive by vm.lastDrive.collectAsState()
    LaunchedEffect(Unit) { vm.observeDrives() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Misfire check", "Is the rough idle a misfire, and which cylinder? A guide, not a diagnosis.") {
            if (s.running) OutlinedButton(onClick = vm::stop) { Text("Stop test") }
            else Button(onClick = onStart, enabled = connected) { Text(if (s.verdict != null) "Run again" else "Start idle test") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SectionCard("Idle test (2 minutes, parked)", Modifier.fillMaxWidth(), icon = AppIcons.Spark) {
                    if (!s.running && s.verdict == null) {
                        Text("1.  Warm engine (drive 10+ minutes first), parked, in Park, A/C off.")
                        Text("2.  Tap Start idle test and don't touch the pedal for 2 minutes.")
                        Text("3.  If you feel the shaking now, note it: it helps read the result.")
                        if (!connected) MutedText("Connect to the car first (Dashboard → Connect).")
                    }
                    if (s.running) {
                        BigValue("${s.secondsLeft / 60}:%02d left".format(s.secondsLeft % 60))
                        LinearProgressIndicator(
                            progress = { 1f - s.secondsLeft.toFloat() / s.totalSeconds },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MutedText(listOfNotNull(s.rpm?.let { "${it.toInt()} rpm" }, s.coolant?.let { "coolant ${it.toInt()} °C" }).joinToString(" · "))
                    }
                    s.message?.let { Text(it, color = Status.Warning.color()) }
                    if (s.running || s.newMisfires.isNotEmpty()) {
                        Text("New misfires during the test", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            (1..4).forEach { c -> CylinderTile(c, s.newMisfires[c], s.newMisfires.containsKey(c), Modifier.weight(1f)) }
                        }
                    }
                }
                SectionCard("Drive watch", Modifier.fillMaxWidth(), icon = Icons.Filled.Info) {
                    MutedText("Misfires under load (driving) can differ from idle. Record a drive on Live data: misfires are " +
                        "counted at the start and end of the drive and shown here after you stop. Nothing to watch while driving.")
                    val d = drive
                    if (d == null) MutedText("No drive with a misfire result yet.")
                    else Text(d.misfireSummary ?: "", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onOpenLive) { Text("Open Live data to record a drive") }
                }
            }
            SectionCard("Result", Modifier.weight(1f), icon = Icons.Filled.CheckCircle) {
                val v = s.verdict
                if (v == null) {
                    MutedText("The result appears here after the idle test.")
                    MutedText("Rough idle on this car is usually one of: a misfire (coil, plug, injector), an EVAP purge valve " +
                        "stuck open (linked to your P145C), an air leak, or worn engine mounts. The test separates them.")
                } else {
                    val bad = v.findings.any { it.level == Finding.Watch }
                    BigValue(v.headline, if (bad) Status.Warning else Status.Ok)
                    v.findings.forEach { f ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                when (f.level) { Finding.Good -> Icons.Filled.CheckCircle; Finding.Watch -> Icons.Filled.Warning; Finding.Info -> Icons.Filled.Info },
                                null, Modifier.size(22.dp),
                                tint = when (f.level) { Finding.Good -> Status.Ok; Finding.Watch -> Status.Warning; Finding.Info -> Status.Neutral }.color(),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(f.title, style = MaterialTheme.typography.titleSmall)
                                MutedText(f.detail)
                            }
                        }
                    }
                    MutedText("If the check-engine light ever FLASHES, misfires are severe: drive gently and get it checked soon.")
                }
            }
        }
    }
}

@Composable
private fun CylinderTile(cylinder: Int, count: Int?, reported: Boolean, modifier: Modifier) {
    val status = when {
        !reported || count == null -> Status.Neutral
        count > 0 -> Status.Warning
        else -> Status.Ok
    }
    Card(modifier, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Cylinder $cylinder", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (reported && count != null) "$count" else "—", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold, color = if (status == Status.Neutral) MaterialTheme.colorScheme.onSurface else status.color())
        }
    }
}
