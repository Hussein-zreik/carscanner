package com.crvdiag.app.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.components.StatusPill
import com.crvdiag.app.ui.icons.AppIcons
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color
import com.crvdiag.obd.CheckResult
import com.crvdiag.obd.PidValue

/** Dial gauges: PID, label, range in base units. */
private data class Dial(val pid: Int, val label: String, val min: Double, val max: Double, val warnAbove: Double? = null)

private val DIALS = listOf(
    Dial(0x0C, "RPM", 0.0, 7000.0, warnAbove = 6500.0),
    Dial(0x05, "Coolant", 40.0, 120.0, warnAbove = 105.0),
    Dial(0x04, "Engine load", 0.0, 100.0),
    Dial(0x11, "Throttle", 0.0, 100.0),
)

/** Short plain names for the tiles (decoder names are longer). */
private val TILE_NAMES = mapOf(
    0x0D to "Speed", 0x0B to "Manifold pressure (absolute)", 0x10 to "Air flow (MAF)", 0x0F to "Intake air temp",
    0x0E to "Ignition timing", 0x06 to "Short-term fuel trim", 0x07 to "Long-term fuel trim",
    0x15 to "Downstream O2", 0x03 to "Fuel system", 0x42 to "Module voltage", 0x44 to "Commanded λ",
    0x24 to "Upstream A/F (λ)", 0x34 to "Upstream A/F (λ)", 0x2E to "EVAP purge", 0x2F to "Fuel level",
    0x46 to "Outside air temp", 0x3C to "Catalyst temp", 0x33 to "Barometric pressure", 0x1F to "Run time",
    0x31 to "Distance since clear", 0x30 to "Warm-ups since clear",
)

@Composable
fun LiveDataScreen(connection: ConnectionViewModel, vm: LiveViewModel) {
    val state by vm.state.collectAsState()
    val units by vm.units.collectAsState()
    val carLink by connection.carLink.collectAsState()
    val ready = carLink is CarLinkState.Ready

    // Poll only while this screen is open, so other screens get the adapter back.
    DisposableEffect(ready) {
        if (ready) vm.start(connection.elmOrNull(), carLink, connection.log)
        onDispose { vm.stop() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Live data", "Real-time engine readings for checks while parked.") {
            FilterChip(units.fahrenheit, { vm.setUnits(units.copy(fahrenheit = !units.fahrenheit)) }, { Text("°F") })
            FilterChip(units.mph, { vm.setUnits(units.copy(mph = !units.mph)) }, { Text("mph") })
            FilterChip(units.psi, { vm.setUnits(units.copy(psi = !units.psi)) }, { Text("psi") })
            if (state.running) {
                StatusPill("%.1f updates/s".format(state.updatesPerSecond), Status.Ok)
                OutlinedButton(onClick = vm::stop) { Text("Pause") }
            } else if (ready) {
                Button(onClick = { vm.start(connection.elmOrNull(), carLink, connection.log) }, enabled = !state.starting) {
                    Text(if (state.starting) "Starting…" else "Start")
                }
            }
        }

        when {
            !ready -> SectionCard("Not connected", icon = Icons.Filled.Info) {
                MutedText("Connect to the car first (Connection). Start the engine for most readings; park the car.")
            }
            state.moving -> MovingNotice()
            else -> {
                state.message?.let { Text(it, color = Status.Warning.color()) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            DIALS.forEach { d -> DialCard(d, state.values[d.pid], units, Modifier.weight(1f)) }
                        }
                        val tiles = LiveTilesOrder.filter { it in state.values }
                        tiles.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { pid ->
                                    ValueTile(TILE_NAMES[pid] ?: state.values.getValue(pid).name, state.values.getValue(pid),
                                        state.history[pid].orEmpty(), units, Modifier.weight(1f))
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (state.running && !state.multiPid) {
                            MutedText("Note: the adapter answers one reading at a time, so updates are slower.")
                        }
                    }
                    ChecksCard(state, Modifier.weight(1f))
                }
            }
        }
    }
}

private val LiveTilesOrder = listOf(
    0x0D, 0x06, 0x07, 0x15, 0x34, 0x24, 0x44, 0x42, 0x0B, 0x10, 0x0F, 0x0E, 0x03, 0x2E, 0x2F, 0x46,
    0x3C, 0x33, 0x1F, 0x31, 0x30,
)

@Composable
private fun MovingNotice() {
    SectionCard("Vehicle moving", Modifier.fillMaxWidth(), icon = Icons.Filled.Warning, iconTint = Status.Warning.color()) {
        Text("Live view is hidden while the car is moving.", style = MaterialTheme.typography.headlineSmall)
        MutedText("Keep your eyes on the road. Readings continue in the background and reappear when you stop.")
    }
}

@Composable
private fun DialCard(dial: Dial, value: PidValue?, units: UnitPrefs, modifier: Modifier) {
    val x = value?.value
    val warn = dial.warnAbove != null && x != null && x > dial.warnAbove
    val color = if (warn) Status.Warning.color() else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Card(modifier, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (x != null) {
                        val f = ((x - dial.min) / (dial.max - dial.min)).coerceIn(0.0, 1.0).toFloat()
                        drawArc(color, 135f, 270f * f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                val (shown, unit) = value?.let(units::convert) ?: (null to "")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(units.number(shown, value?.unit ?: ""), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(dial.label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ValueTile(name: String, value: PidValue, history: List<Double>, units: UnitPrefs, modifier: Modifier) {
    Card(modifier, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(units.text(value), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Sparkline(history, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(28.dp))
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val range = (values.max() - min).takeIf { it > 1e-9 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = size.width * i / (values.size - 1)
            val py = size.height - (size.height * ((v - min) / range)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ChecksCard(state: LiveUiState, modifier: Modifier) {
    SectionCard("Quick checks", modifier, icon = AppIcons.Gauge) {
        MutedText("A guide, not a diagnosis. Each check runs when its conditions are met.")
        if (state.checks.isEmpty()) MutedText("Waiting for readings…")
        state.checks.forEach { c ->
            val status = when (c.result) {
                CheckResult.Good -> Status.Ok
                CheckResult.Watch -> Status.Warning
                CheckResult.Waiting -> Status.Neutral
            }
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    when (c.result) {
                        CheckResult.Good -> Icons.Filled.CheckCircle
                        CheckResult.Watch -> Icons.Filled.Warning
                        CheckResult.Waiting -> Icons.Filled.Info
                    },
                    null, tint = status.color(), modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(c.title, style = MaterialTheme.typography.titleSmall)
                    MutedText(c.detail)
                }
            }
        }
    }
}
