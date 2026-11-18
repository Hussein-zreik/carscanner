package com.crvdiag.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crvdiag.app.codes.CodesState
import com.crvdiag.app.codes.CodesViewModel
import com.crvdiag.app.codes.mainReadiness
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.connection.LinkState
import com.crvdiag.app.connection.SavedLink
import com.crvdiag.app.maintenance.MaintenanceState
import com.crvdiag.app.maintenance.MaintenanceViewModel
import com.crvdiag.app.maintenance.describe
import com.crvdiag.app.maintenance.miles
import com.crvdiag.app.ui.Destination
import com.crvdiag.app.ui.components.BigValue
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.components.StatusPill
import com.crvdiag.app.ui.icons.AppIcons
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color

@Composable
fun DashboardScreen(
    connection: ConnectionViewModel,
    codes: CodesViewModel,
    maintenance: MaintenanceViewModel,
    onNavigate: (Destination) -> Unit,
    onQuickConnect: () -> Unit,
) {
    val link by connection.state.collectAsState()
    val carLink by connection.carLink.collectAsState()
    val saved by connection.savedLink.collectAsState()
    val codesState by codes.state.collectAsState()
    val service by maintenance.state.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("CR-V Diag", "2011 Honda CR-V EX · 2.4 L · 4WD · automatic") {
            val (text, status) = linkSummary(link, carLink)
            StatusPill(text, status)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            ConnectionTile(link, carLink, saved, onQuickConnect, connection::disconnect, { onNavigate(Destination.Connection) }, tile)
            EngineLightTile(codesState, carLink is CarLinkState.Ready, { onNavigate(Destination.Codes) }, tile)
            SelfTestTile(codesState, tile)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            ServiceTile(service, { onNavigate(Destination.Maintenance) }, tile)
            BatteryTile((carLink as? CarLinkState.Ready)?.report?.voltage, tile)
            SectionCard("Live data", tile, icon = AppIcons.Gauge) {
                BigValue("Gauges")
                MutedText("RPM, temperatures, fuel trims, O2 sensors and charging voltage, with quick checks. Use while parked.")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onNavigate(Destination.Live) }) { Text("Open live data") }
            }
            SectionCard("Airbag, ABS & tire pressure", tile, icon = Icons.Filled.Info, iconTint = Status.Warning.color()) {
                BigValue("Not readable", Status.Warning)
                MutedText(
                    "This adapter can't reach these systems. They're planned with the OBDLink MX+ adapter. " +
                        "If one of their warning lights is on, get a professional scan.",
                )
            }
        }
    }
}

@Composable
private fun ServiceTile(state: MaintenanceState, onOpen: () -> Unit, modifier: Modifier) {
    val top = state.byUrgency.firstOrNull()
    val status = when {
        state.overdueCount > 0 -> Status.Fault
        state.soonCount > 0 -> Status.Warning
        state.items.isNotEmpty() && state.unknownCount == 0 -> Status.Ok
        else -> Status.Neutral
    }
    SectionCard("Service", modifier, icon = Icons.Filled.DateRange, iconTint = status.color()) {
        when {
            state.odometer == null -> {
                BigValue("Set up")
                MutedText("Add an odometer reading and when items were last done.")
            }
            state.overdueCount > 0 || state.soonCount > 0 -> {
                BigValue(if (state.overdueCount > 0) "${state.overdueCount} overdue" else "${state.soonCount} due soon", status)
                top?.let { MutedText("${it.item.name}: ${it.status.describe().replaceFirstChar { c -> c.lowercase() }}") }
                if (state.overdueCount > 0 && state.soonCount > 0) MutedText("Also ${state.soonCount} due soon.")
            }
            else -> {
                BigValue(if (state.unknownCount > 0) "${state.unknownCount} unknown" else "Up to date", status)
                MutedText("Odometer ≈ ${miles(state.odometer.miles)}")
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text("Open service") }
    }
}

fun linkSummary(link: LinkState, carLink: CarLinkState): Pair<String, Status> = when {
    carLink is CarLinkState.Ready -> "Connected" to Status.Ok
    carLink is CarLinkState.Failed -> "Car not answering" to Status.Fault
    link is LinkState.Connecting || carLink is CarLinkState.Initializing -> "Connecting…" to Status.Busy
    link is LinkState.Failed -> "Connection failed" to Status.Fault
    else -> "Not connected" to Status.Neutral
}

@Composable
private fun ConnectionTile(
    link: LinkState,
    carLink: CarLinkState,
    saved: SavedLink,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier,
) {
    val (text, status) = linkSummary(link, carLink)
    SectionCard("Connection", modifier, icon = AppIcons.Plug, iconTint = status.color()) {
        BigValue(text, if (status == Status.Ok) Status.Ok else Status.Neutral)
        MutedText(
            when {
                carLink is CarLinkState.Ready -> listOfNotNull(
                    (link as? LinkState.Connected)?.label?.substringBefore(" ·"),
                    carLink.report.adapterId,
                    carLink.report.detected?.protocol?.let { if (it.isCan) "CAN network" else it.label },
                ).joinToString(" · ")
                carLink is CarLinkState.Failed -> carLink.hint
                carLink is CarLinkState.Initializing -> carLink.step
                link is LinkState.Failed -> link.message
                else -> "Ignition ON, tablet on the adapter's Wi-Fi, then tap Connect."
            },
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val busy = link is LinkState.Connecting || link is LinkState.Connected
            if (busy) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            else Button(onClick = onConnect) { Text(if (saved is SavedLink.Wifi) "Connect (Wi-Fi)" else "Connect (Bluetooth)") }
            TextButton(onClick = onOptions) { Text("Options") }
        }
    }
}

@Composable
private fun EngineLightTile(state: CodesState, connected: Boolean, onOpen: () -> Unit, modifier: Modifier) {
    val report = (state as? CodesState.Done)?.report
    val mil = report?.milOn
    val stored = report?.stored?.values?.flatten().orEmpty()
    val pending = report?.pending?.values?.flatten().orEmpty()
    val status = when {
        report == null -> Status.Neutral
        mil == null || !report.storedRead || report.milWithoutStoredCode -> Status.Warning
        mil == true || stored.isNotEmpty() -> Status.Fault
        pending.isNotEmpty() -> Status.Warning
        else -> Status.Ok
    }
    SectionCard(
        "Check-engine light", modifier,
        icon = if (status == Status.Ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        iconTint = status.color(),
    ) {
        when {
            state is CodesState.Busy -> { BigValue("Reading…"); MutedText(state.step) }
            report == null -> {
                BigValue("—")
                MutedText(if (connected) "Codes not read yet." else "Connect to read the car's codes.")
            }
            else -> {
                BigValue(
                    when (mil) { true -> "ON"; false -> "OFF"; null -> "Unknown" },
                    when (mil) { true -> Status.Fault; false -> Status.Ok; null -> Status.Warning },
                )
                when {
                    !report.storedRead -> Text("Couldn't read the stored codes. Open Codes and tap Read again.", color = Status.Warning.color())
                    report.milWithoutStoredCode -> Text("Light is ON but no code was received. Open Codes and tap Read again.", color = Status.Warning.color())
                    else -> MutedText(
                        "${if (stored.isEmpty()) "No" else stored.size} stored code${if (stored.size == 1) "" else "s"} · " +
                            "${if (pending.isEmpty()) "no" else pending.size} pending" +
                            (pending.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")") ?: ""),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text("View codes") }
    }
}

@Composable
private fun SelfTestTile(state: CodesState, modifier: Modifier) {
    val readiness = (state as? CodesState.Done)?.report?.let(::mainReadiness)
    val available = readiness?.monitors?.filter { it.available }.orEmpty()
    val done = available.count { it.complete }
    val status = when {
        readiness == null -> Status.Neutral
        done == available.size -> Status.Ok
        else -> Status.Warning
    }
    SectionCard("Emission self-tests", modifier, icon = Icons.Filled.CheckCircle, iconTint = status.color()) {
        if (readiness == null) {
            BigValue("—")
            MutedText("Shown after the codes are read.")
        } else {
            BigValue("$done of ${available.size} done", status)
            LinearProgressIndicator(
                progress = { if (available.isEmpty()) 0f else done.toFloat() / available.size },
                modifier = Modifier.fillMaxWidth(),
                color = status.color(),
            )
            val waiting = readiness.incomplete.map { it.name }
            MutedText(
                if (waiting.isEmpty()) "All self-tests complete."
                else "Not finished: ${waiting.joinToString()}. They finish with normal driving over a few days.",
            )
        }
    }
}

@Composable
private fun BatteryTile(voltage: String?, modifier: Modifier) {
    val v = voltage?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
    val (verdict, status) = when {
        v == null -> "Shown after connecting." to Status.Neutral
        v > 14.8 -> "Too high: have the charging system checked." to Status.Fault
        v >= 13.5 -> "Charging normally (engine running)." to Status.Ok
        v >= 12.4 -> "Good." to Status.Ok
        v >= 12.0 -> "A bit low. Re-check after the car sits overnight (healthy: 12.4 V or more)." to Status.Warning
        else -> "Low. Re-check at rest; consider a battery test." to Status.Warning
    }
    SectionCard("Battery voltage", modifier, icon = Icons.Filled.Info, iconTint = status.color()) {
        BigValue(v?.let { "%.1f V".format(it) } ?: "—", status)
        MutedText(verdict)
        if (v != null) {
            Text(
                "Measured by the adapter when connecting (approximate). Engine running should read 13.7–14.7 V.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
