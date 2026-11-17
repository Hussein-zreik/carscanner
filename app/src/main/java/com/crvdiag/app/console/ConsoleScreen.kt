package com.crvdiag.app.console

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crvdiag.transport.PairedDevice
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.WifiTcpTransport
import kotlinx.coroutines.launch

private enum class LinkType { Bluetooth, WiFi }

private val QUICK_COMMANDS = listOf("ATZ", "ATI", "ATRV", "ATDPN", "0100")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(onBack: () -> Unit, vm: ConsoleViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val entries by vm.log.entries.collectAsState()
    val paired by vm.paired.collectAsState()
    val bluetoothOff by vm.bluetoothOff.collectAsState()
    val carLink by vm.carLink.collectAsState()
    val savedProtocol by vm.savedProtocol.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // Android 12+: BLUETOOTH_CONNECT. Android 17+: ACCESS_LOCAL_NETWORK for the Wi-Fi socket.
    val runtimePermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refreshPairedDevices() }
    // Opens Android's own "Allow app to turn on Bluetooth?" dialog.
    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { vm.refreshPairedDevices() }
    LaunchedEffect(Unit) {
        val missing = runtimePermissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.refreshPairedDevices() else permissionLauncher.launch(missing.toTypedArray())
    }

    var linkType by rememberSaveable { mutableStateOf(LinkType.Bluetooth) }
    var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var host by rememberSaveable { mutableStateOf(WifiTcpTransport.DEFAULT_HOST) }
    var port by rememberSaveable { mutableStateOf(WifiTcpTransport.DEFAULT_PORT.toString()) }
    var input by rememberSaveable { mutableStateOf("") }

    // Pre-select the adapter: first paired device whose name looks like an OBD dongle.
    LaunchedEffect(paired) {
        if (selectedAddress == null || paired.none { it.address == selectedAddress }) {
            selectedAddress = (paired.firstOrNull { it.name.contains("OBD", ignoreCase = true) }
                ?: paired.firstOrNull())?.address
        }
    }

    val busy = state is LinkState.Connecting || state is LinkState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adapter console") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("CR-V Diag log", vm.log.export())))
                        }
                    }) { Text("Copy log") }
                    TextButton(onClick = vm::clearLog) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow {
                        LinkType.entries.forEachIndexed { i, type ->
                            SegmentedButton(
                                selected = linkType == type,
                                onClick = { linkType = type },
                                enabled = !busy,
                                shape = SegmentedButtonDefaults.itemShape(i, LinkType.entries.size),
                            ) { Text(if (type == LinkType.WiFi) "Wi-Fi" else "Bluetooth") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (linkType) {
                            LinkType.Bluetooth -> DevicePicker(
                                devices = paired,
                                selected = paired.firstOrNull { it.address == selectedAddress },
                                enabled = !busy,
                                onSelect = { selectedAddress = it.address },
                                onRefresh = vm::refreshPairedDevices,
                                modifier = Modifier.weight(1f),
                            )
                            LinkType.WiFi -> {
                                OutlinedTextField(
                                    value = host, onValueChange = { host = it.trim() }, enabled = !busy,
                                    label = { Text("Adapter IP") }, singleLine = true, modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                                    enabled = !busy, label = { Text("Port") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(110.dp),
                                )
                            }
                        }
                        if (busy) {
                            OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                        } else {
                            Button(
                                onClick = {
                                    when (linkType) {
                                        LinkType.Bluetooth ->
                                            paired.firstOrNull { it.address == selectedAddress }?.let(vm::connectBluetooth)
                                        LinkType.WiFi -> port.toIntOrNull()?.let { vm.connectWifi(host, it) }
                                    }
                                },
                                enabled = when (linkType) {
                                    LinkType.Bluetooth -> selectedAddress != null
                                    LinkType.WiFi -> host.isNotBlank() && port.toIntOrNull() in 1..65535
                                },
                            ) { Text("Connect") }
                        }
                    }
                    if (linkType == LinkType.Bluetooth && bluetoothOff) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tablet Bluetooth is off", color = MaterialTheme.colorScheme.error)
                            @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT requested on screen open.
                            val turnOn = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            Button(onClick = turnOn) { Text("Turn on Bluetooth") }
                        }
                    }
                    Text(statusText(state), style = MaterialTheme.typography.bodyMedium, color = statusColor(state))
                }
            }

            CarLinkCard(
                carLink = carLink,
                savedProtocol = savedProtocol?.let { "${it.code} · ${it.label}" },
                onForgetProtocol = vm::forgetProtocol,
            )

            TrafficView(entries = entries, format = vm.log::format, modifier = Modifier.weight(1f).fillMaxWidth())

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(QUICK_COMMANDS) { cmd ->
                    AssistChip(onClick = { vm.send(cmd) }, enabled = state is LinkState.Connected, label = { Text(cmd) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val submit = {
                    vm.send(input)
                    input = ""
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Command (allow-listed only)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = submit, enabled = state is LinkState.Connected && input.isNotBlank()) { Text("Send") }
            }
        }
    }
}

@Composable
private fun DevicePicker(
    devices: List<PairedDevice>,
    selected: PairedDevice?,
    enabled: Boolean,
    onSelect: (PairedDevice) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && devices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        selected != null -> "${selected.name}  (${selected.address})"
                        devices.isEmpty() -> "No paired devices: pair the adapter in Android Settings"
                        else -> "Choose adapter"
                    },
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text("${device.name}  (${device.address})") },
                        onClick = {
                            onSelect(device)
                            expanded = false
                        },
                    )
                }
            }
        }
        TextButton(onClick = onRefresh, enabled = enabled) { Text("Refresh") }
    }
}

@Composable
private fun TrafficView(
    entries: List<TrafficLog.Entry>,
    format: (TrafficLog.Entry) -> String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    Card(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
            items(entries) { entry ->
                Text(
                    text = format(entry),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.direction) {
                        TrafficLog.Direction.TX -> MaterialTheme.colorScheme.primary
                        TrafficLog.Direction.RX -> MaterialTheme.colorScheme.onSurface
                        TrafficLog.Direction.INFO -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
        }
    }
}

@Composable
private fun CarLinkCard(carLink: CarLinkState, savedProtocol: String?, onForgetProtocol: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Car link", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (savedProtocol != null) {
                    TextButton(onClick = onForgetProtocol) { Text("Re-detect protocol") }
                }
            }
            when (carLink) {
                CarLinkState.Idle -> Text(
                    if (savedProtocol != null) "Saved protocol: $savedProtocol (no auto-search)"
                    else "Protocol will be auto-detected on first connect",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is CarLinkState.Initializing -> Text("Starting… ${carLink.step}", style = MaterialTheme.typography.bodyMedium)
                is CarLinkState.Failed -> {
                    Text("Startup failed at ${carLink.step}: ${carLink.detail}", color = MaterialTheme.colorScheme.error)
                    Text(carLink.hint, style = MaterialTheme.typography.bodyMedium)
                }
                is CarLinkState.Ready -> {
                    val r = carLink.report
                    val protocol = r.detected?.let {
                        "${it.protocol.code} · ${it.protocol.label} " + if (it.automatic) "(auto-detected)" else "(saved)"
                    } ?: "unknown"
                    Text("Adapter: ${r.adapterId ?: "?"}  ·  Voltage: ${r.voltage ?: "?"}")
                    Text("Protocol: $protocol")
                    carLink.ecus.forEachIndexed { i, ecu ->
                        val address = ecu.responseId?.let { "$it (request ${ecu.requestId ?: "?"})" } ?: "ECU ${i + 1} (address unknown)"
                        Text(
                            "$address · ${ecu.supportedPids.size} PIDs: " +
                                ecu.supportedPids.joinToString(" ") { "%02X".format(it) },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    r.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.tertiary) }
                }
            }
        }
    }
}

private fun statusText(state: LinkState) = when (state) {
    LinkState.Disconnected -> "Disconnected"
    is LinkState.Connecting -> "Connecting… ${state.label}"
    is LinkState.Connected -> "Connected: ${state.label}"
    is LinkState.Failed -> "Failed: ${state.message}"
}

@Composable
private fun statusColor(state: LinkState): Color = when (state) {
    is LinkState.Connected -> MaterialTheme.colorScheme.primary
    is LinkState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
