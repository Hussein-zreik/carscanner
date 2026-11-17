package com.crvdiag.app.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.EcuNames
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.components.StepRow
import com.crvdiag.app.ui.icons.AppIcons
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.transport.WifiTcpTransport

private enum class LinkType { WiFi, Bluetooth }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(vm: ConnectionViewModel) {
    val link by vm.state.collectAsState()
    val carLink by vm.carLink.collectAsState()
    val paired by vm.paired.collectAsState()
    val bluetoothOff by vm.bluetoothOff.collectAsState()
    val saved by vm.savedLink.collectAsState()
    val context = LocalContext.current

    var linkType by rememberSaveable { mutableStateOf(if (saved is SavedLink.Bluetooth) LinkType.Bluetooth else LinkType.WiFi) }
    var host by rememberSaveable { mutableStateOf((saved as? SavedLink.Wifi)?.host ?: WifiTcpTransport.DEFAULT_HOST) }
    var port by rememberSaveable { mutableStateOf(((saved as? SavedLink.Wifi)?.port ?: WifiTcpTransport.DEFAULT_PORT).toString()) }
    var selectedAddress by rememberSaveable { mutableStateOf<String?>((saved as? SavedLink.Bluetooth)?.address) }
    var showAddress by rememberSaveable { mutableStateOf(false) }

    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshPairedDevices() }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshPairedDevices() }
    // Android 17+ requires ACCESS_LOCAL_NETWORK for the Wi-Fi adapter socket (not needed on Android 16).
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        port.toIntOrNull()?.let { p -> vm.connectWifi(host, p) }
    }

    LaunchedEffect(linkType) {
        if (linkType == LinkType.Bluetooth) {
            val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            if (needs) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) else vm.refreshPairedDevices()
        }
    }
    LaunchedEffect(paired) {
        if (selectedAddress == null || paired.none { it.address == selectedAddress }) {
            selectedAddress = (paired.firstOrNull { it.name.contains("OBD", ignoreCase = true) } ?: paired.firstOrNull())?.address
        }
    }

    val busy = link is LinkState.Connecting || link is LinkState.Connected

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Connection", "Link the tablet to the OBD adapter plugged in under the dashboard.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionCard("How to connect", Modifier.weight(1f), icon = AppIcons.Plug) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LinkType.entries.forEachIndexed { i, type ->
                        SegmentedButton(
                            selected = linkType == type,
                            onClick = { linkType = type },
                            enabled = !busy,
                            shape = SegmentedButtonDefaults.itemShape(i, LinkType.entries.size),
                        ) { Text(if (type == LinkType.WiFi) "Wi-Fi (recommended)" else "Bluetooth") }
                    }
                }
                when (linkType) {
                    LinkType.WiFi -> {
                        Text("1.  Turn the ignition ON (engine can stay off).")
                        Text("2.  In Android Settings → Wi-Fi, join the adapter's network. If Android warns \"no internet\", keep the connection.")
                        Text("3.  Close other OBD apps (like Car Scanner): the adapter talks to one app at a time.")
                        Text("4.  Tap Connect.")
                        TextButton(onClick = { showAddress = !showAddress }) {
                            Text(if (showAddress) "Hide adapter address" else "Adapter address: $host : $port")
                        }
                        if (showAddress) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = host, onValueChange = { host = it.trim() }, enabled = !busy,
                                    label = { Text("IP address") }, singleLine = true, modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, enabled = !busy,
                                    label = { Text("Port") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(120.dp),
                                )
                            }
                        }
                    }
                    LinkType.Bluetooth -> {
                        MutedText("Bluetooth hasn't connected with this adapter yet. Wi-Fi is the reliable choice for now.")
                        if (bluetoothOff) {
                            @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT requested when this tab opens.
                            val turnOn = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            OutlinedButton(onClick = turnOn) { Text("Turn on Bluetooth") }
                        } else {
                            DevicePicker(paired.map { it.name to it.address }, selectedAddress, !busy, { selectedAddress = it }, vm::refreshPairedDevices)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (busy) {
                    OutlinedButton(onClick = vm::disconnect, Modifier.fillMaxWidth()) { Text("Disconnect") }
                } else {
                    Button(
                        onClick = {
                            when (linkType) {
                                LinkType.WiFi -> {
                                    val p = port.toIntOrNull() ?: return@Button
                                    val needsLocal = Build.VERSION.SDK_INT >= 37 &&
                                        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
                                    if (needsLocal) localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) else vm.connectWifi(host, p)
                                }
                                LinkType.Bluetooth -> paired.firstOrNull { it.address == selectedAddress }?.let(vm::connectBluetooth)
                            }
                        },
                        enabled = when (linkType) {
                            LinkType.WiFi -> host.isNotBlank() && port.toIntOrNull() in 1..65535
                            LinkType.Bluetooth -> selectedAddress != null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Connect") }
                }
            }

            SectionCard("Progress", Modifier.weight(1f), icon = Icons.Filled.Info) {
                val steps = progressSteps(link, carLink)
                steps.forEach { StepRow(it.label, it.detail, it.status) }
                val failure = (carLink as? CarLinkState.Failed)?.hint ?: (link as? LinkState.Failed)?.message
                failure?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("What to do: $it", color = MaterialTheme.colorScheme.error)
                }
                (carLink as? CarLinkState.Ready)?.let { ready ->
                    Spacer(Modifier.height(4.dp))
                    Text("Car computers found", style = MaterialTheme.typography.titleSmall)
                    ready.ecus.forEach { ecu ->
                        val id = ecu.responseId ?: "?"
                        Text("• ${EcuNames.label(id, ready.ecus)}  ·  supports ${ecu.supportedPids.size} data items")
                    }
                    ready.report.warnings.forEach { MutedText("Note: $it") }
                }
            }
        }
    }
}

private data class Step(val label: String, val detail: String?, val status: Status)

private fun progressSteps(link: LinkState, carLink: CarLinkState): List<Step> {
    val phase = (carLink as? CarLinkState.Initializing)?.phase
    val failedStep = (carLink as? CarLinkState.Failed)?.step
    val ready = carLink as? CarLinkState.Ready

    val linkStep = when (link) {
        is LinkState.Connecting -> Step("Reach the adapter", "Opening ${link.label.substringBefore(" ·")} link…", Status.Busy)
        is LinkState.Connected -> Step("Reach the adapter", link.label.substringBefore(" ·") + " link open", Status.Ok)
        is LinkState.Failed -> Step("Reach the adapter", "Could not reach it", Status.Fault)
        LinkState.Disconnected -> Step("Reach the adapter", null, Status.Neutral)
    }
    val adapterStep = when {
        ready != null -> Step("Prepare the adapter", ready.report.adapterId, Status.Ok)
        phase == InitPhase.Adapter -> Step("Prepare the adapter", "Resetting and setting up…", Status.Busy)
        phase != null -> Step("Prepare the adapter", null, Status.Ok)
        failedStep != null && failedStep != "0100" -> Step("Prepare the adapter", "Adapter did not respond as expected", Status.Fault)
        failedStep == "0100" -> Step("Prepare the adapter", null, Status.Ok)
        else -> Step("Prepare the adapter", null, Status.Neutral)
    }
    val carStep = when {
        ready != null -> Step("Talk to the car", "${ready.ecus.size} computer(s) answered", Status.Ok)
        phase == InitPhase.Car -> Step("Talk to the car", "Asking the car… (first time can take ~10 s)", Status.Busy)
        phase == InitPhase.Network -> Step("Talk to the car", null, Status.Ok)
        failedStep == "0100" -> Step("Talk to the car", "The car did not answer", Status.Fault)
        else -> Step("Talk to the car", null, Status.Neutral)
    }
    val networkStep = when {
        ready?.report?.detected != null -> {
            val d = ready.report.detected!!
            Step(
                "Identify the car's network",
                (if (d.protocol.isCan) "CAN bus, 500 kbps" else d.protocol.label) + if (d.automatic) " (found automatically)" else " (remembered)",
                Status.Ok,
            )
        }
        ready != null -> Step("Identify the car's network", "Unknown", Status.Warning)
        phase == InitPhase.Network -> Step("Identify the car's network", "Checking…", Status.Busy)
        else -> Step("Identify the car's network", null, Status.Neutral)
    }
    return listOf(linkStep, adapterStep, carStep, networkStep)
}

@Composable
private fun DevicePicker(
    devices: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && devices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(
                    devices.firstOrNull { it.second == selected }?.first
                        ?: if (devices.isEmpty()) "No paired adapter: pair it in Android Settings first" else "Choose adapter",
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                devices.forEach { (name, address) ->
                    DropdownMenuItem(text = { Text("$name  ($address)") }, onClick = { onSelect(address); expanded = false })
                }
            }
        }
        TextButton(onClick = onRefresh, enabled = enabled) { Text("Refresh") }
    }
}
