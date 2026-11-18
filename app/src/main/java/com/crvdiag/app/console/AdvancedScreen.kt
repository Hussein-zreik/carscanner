@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.crvdiag.app.console

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.connection.LinkState
import com.crvdiag.app.ui.EcuNames
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color
import com.crvdiag.transport.TrafficLog
import kotlinx.coroutines.launch

private val QUICK_COMMANDS = listOf("ATI", "ATRV", "ATDPN", "0100", "0101")

/**
 * Technical tools: adapter/protocol details, allow-listed raw commands and the full
 * communication log (the "Debug screen" from CLAUDE.md).
 */
@Composable
fun AdvancedScreen(vm: ConnectionViewModel, onOpenCapture: () -> Unit = {}) {
    val link by vm.state.collectAsState()
    val carLink by vm.carLink.collectAsState()
    val savedProtocol by vm.savedProtocol.collectAsState()
    val entries by vm.log.entries.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val connected = link is LinkState.Connected

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("Advanced", "Technical details, raw adapter commands and the full communication log.")
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.width(440.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SectionCard("Technical details", Modifier.fillMaxWidth(), icon = Icons.Filled.Info) {
                    when (val c = carLink) {
                        is CarLinkState.Ready -> {
                            Detail("Adapter", c.report.adapterId ?: "?")
                            Detail("Voltage (ATRV)", c.report.voltage ?: "?")
                            Detail(
                                "Protocol",
                                c.report.detected?.let { "${it.protocol.code} · ${it.protocol.label}" + if (it.automatic) " (auto)" else " (saved)" } ?: "?",
                            )
                            Detail("Headers", if (c.report.headersOn) "on" else "off")
                            c.ecus.forEach { ecu ->
                                Detail(
                                    "${ecu.responseId ?: "?"} → ${ecu.requestId ?: "?"}",
                                    "${EcuNames.label(ecu.responseId ?: "?", c.ecus)} · ${ecu.supportedPids.size} PIDs",
                                )
                            }
                            c.report.warnings.forEach { Text("⚠ $it", color = Status.Warning.color()) }
                        }
                        is CarLinkState.Failed -> Text("Startup failed at ${c.step}: ${c.detail}", color = MaterialTheme.colorScheme.error)
                        is CarLinkState.Initializing -> MutedText(c.step)
                        CarLinkState.Idle -> MutedText("Not connected.")
                    }
                    Detail("Saved protocol", savedProtocol?.let { "${it.code} · ${it.label}" } ?: "none (auto-detect next time)")
                    if (savedProtocol != null) {
                        OutlinedButton(onClick = vm::forgetProtocol) { Text("Re-detect protocol on next connect") }
                    }
                }
                SectionCard("Honda capture (Phase 2)", Modifier.fillMaxWidth(), icon = Icons.Filled.Build) {
                    MutedText("Record the Honda-only requests Car Scanner sends (transmission temperature, knock, VTC…), " +
                        "relayed read-only through this tablet.")
                    OutlinedButton(onClick = onOpenCapture) { Text("Open capture mode") }
                }
                SectionCard("Send a command", Modifier.fillMaxWidth(), icon = Icons.Filled.Build) {
                    MutedText("Only allow-listed, read-only commands are sent. Anything else is refused and logged.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QUICK_COMMANDS.forEach { cmd ->
                            AssistChip(onClick = { vm.send(cmd) }, enabled = connected, label = { Text(cmd) })
                        }
                    }
                    val submit = {
                        vm.send(input)
                        input = ""
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            label = { Text("Command") }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = submit, enabled = connected && input.isNotBlank()) { Text("Send") }
                    }
                }
            }
            SectionCard("Communication log", Modifier.weight(1f).fillMaxHeight(), icon = Icons.AutoMirrored.Filled.List) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("CR-V Diag log", vm.log.export()))) }
                    }) { Text("Copy log") }
                    TextButton(onClick = vm::clearLog) { Text("Clear") }
                    MutedText("${entries.size} lines · paste the copied log in chat when asked", Modifier.padding(top = 10.dp))
                }
                TrafficView(entries, vm.log::format, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(140.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TrafficView(entries: List<TrafficLog.Entry>, format: (TrafficLog.Entry) -> String, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex) }
    LazyColumn(state = listState, modifier = modifier) {
        items(entries) { entry ->
            Text(
                format(entry),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = when (entry.direction) {
                    TrafficLog.Direction.TX -> MaterialTheme.colorScheme.primary
                    TrafficLog.Direction.RX -> MaterialTheme.colorScheme.onSurface
                    TrafficLog.Direction.INFO -> Status.Warning.color()
                },
            )
        }
    }
}
