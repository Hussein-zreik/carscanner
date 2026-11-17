@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.crvdiag.app.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.components.BigValue
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.components.StatusPill
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color
import com.crvdiag.data.DueStatus
import com.crvdiag.data.MaintenanceItemEntity
import com.crvdiag.data.MaintenanceLogic
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val SHOW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

fun miles(n: Int) = "%,d mi".format(n)

fun DueStatus.toStatus(): Status = when (this) {
    is DueStatus.Overdue -> Status.Fault
    is DueStatus.Soon -> Status.Warning
    DueStatus.Unknown -> Status.Neutral
    is DueStatus.Ok -> Status.Ok
}

/** "in 1,200 mi or 3 months", "overdue by 400 mi", "no record yet". */
fun DueStatus.describe(): String {
    fun left(m: Int?, d: Long?, joiner: String = " or ") = listOfNotNull(m?.let { miles(it) }, d?.let { days(it) }).joinToString(joiner)
    return when (this) {
        is DueStatus.Overdue -> "Overdue by " + left(milesOver, daysOver, " and ").ifEmpty { "now" }
        is DueStatus.Soon -> "Due soon: in " + left(milesLeft, daysLeft)
        is DueStatus.Ok -> "Next in " + left(milesLeft, daysLeft)
        DueStatus.Unknown -> "No record with enough detail yet"
    }
}

private fun days(d: Long): String = when {
    d >= 60 -> "${d / 30} months"
    d >= 14 -> "${d / 7} weeks"
    else -> "$d days"
}

private fun intervalText(item: MaintenanceItemEntity) =
    listOfNotNull(item.intervalMiles?.let { miles(it) }, item.intervalMonths?.let { "$it months" })
        .joinToString(" or ").let { if (it.isEmpty()) "No interval set" else "Every $it, whichever comes first" }

@Composable
fun MaintenanceScreen(vm: MaintenanceViewModel) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.observe() }

    var readingDialog by remember { mutableStateOf(false) }
    var serviceFor by remember { mutableStateOf<MaintenanceItemEntity?>(null) }
    var intervalFor by remember { mutableStateOf<MaintenanceItemEntity?>(null) }
    var removeRecord by remember { mutableStateOf<Long?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Maintenance", "Based on your own odometer readings (the car's odometer is branded \"not actual\").") {
            Button(onClick = { readingDialog = true }) { Text("Add odometer reading") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SectionCard("Odometer", Modifier.fillMaxWidth(), icon = Icons.Filled.DateRange) {
                    val odo = state.odometer
                    if (odo == null) {
                        BigValue("—")
                        MutedText("Add your first odometer reading to start.")
                    } else {
                        BigValue((if (odo.estimated) "≈ " else "") + miles(odo.miles))
                        val latest = state.readings.maxByOrNull { it.epochDay }
                        MutedText(
                            listOfNotNull(
                                latest?.let { "Last reading ${miles(it.miles)} on ${SHOW_DATE.format(LocalDate.ofEpochDay(it.epochDay))}" },
                                odo.milesPerDay?.let { "You drive about ${"%,d".format((it * 30).toInt())} mi a month" },
                                "estimated for today".takeIf { odo.estimated },
                            ).joinToString(" · "),
                        )
                    }
                }
                SectionCard("Summary", Modifier.fillMaxWidth(), icon = Icons.Filled.Info) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.overdueCount > 0) StatusPill("${state.overdueCount} overdue", Status.Fault)
                        if (state.soonCount > 0) StatusPill("${state.soonCount} due soon", Status.Warning)
                        if (state.unknownCount > 0) StatusPill("${state.unknownCount} unknown", Status.Neutral)
                        if (state.overdueCount + state.soonCount + state.unknownCount == 0 && state.items.isNotEmpty()) StatusPill("All up to date", Status.Ok)
                    }
                    MutedText("Intervals are starting points: confirm them in your owner's manual and tap Interval to change any.")
                    MutedText("\"Unknown\" means there's no record yet: log when it was last done, or consider doing it now.")
                }
                SectionCard("Service history", Modifier.fillMaxWidth(), icon = Icons.Filled.DateRange) {
                    if (state.records.isEmpty()) MutedText("Nothing logged yet. Use Log service on any item.")
                    val names = state.items.associate { it.item.key to it.item.name }
                    state.records.take(12).forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(names[r.itemKey] ?: r.itemKey, fontWeight = FontWeight.SemiBold)
                                MutedText(
                                    listOfNotNull(
                                        SHOW_DATE.format(LocalDate.ofEpochDay(r.epochDay)),
                                        r.odometerMiles?.let { miles(it) } ?: "mileage unknown",
                                        r.notes,
                                    ).joinToString(" · "),
                                )
                            }
                            TextButton(onClick = { removeRecord = r.id }) { Text("Remove") }
                        }
                    }
                }
            }
            SectionCard("What's due", Modifier.weight(2f), icon = Icons.Filled.Info) {
                state.byUrgency.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.item.name, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(10.dp))
                                StatusPill(
                                    when (s.status) {
                                        is DueStatus.Overdue -> "Overdue"
                                        is DueStatus.Soon -> "Due soon"
                                        DueStatus.Unknown -> "Unknown"
                                        is DueStatus.Ok -> "OK"
                                    },
                                    s.status.toStatus(),
                                )
                            }
                            Text(s.status.describe(), color = if (s.status is DueStatus.Ok) MaterialTheme.colorScheme.onSurface else s.status.toStatus().color())
                            MutedText(
                                listOfNotNull(
                                    intervalText(s.item),
                                    s.last?.let { "last done ${SHOW_DATE.format(LocalDate.ofEpochDay(it.epochDay))}" + (it.odometerMiles?.let { m -> " at ${miles(m)}" } ?: "") },
                                ).joinToString(" · "),
                            )
                            s.item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            OutlinedButton(onClick = { serviceFor = s.item }) { Text("Log service") }
                            TextButton(onClick = { intervalFor = s.item }) { Text("Interval") }
                        }
                    }
                }
            }
        }
    }

    removeRecord?.let { id ->
        AlertDialog(
            onDismissRequest = { removeRecord = null },
            title = { Text("Remove this service record?") },
            text = { Text("The item's due date is recalculated from the remaining records.") },
            confirmButton = { TextButton(onClick = { vm.deleteRecord(id); removeRecord = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeRecord = null }) { Text("Cancel") } },
        )
    }
    if (readingDialog) {
        ReadingDialog(
            check = { m, d -> MaintenanceLogic.checkReading(m, d, state.readings) },
            onSave = { m, d -> vm.addReading(m, d); readingDialog = false },
            onDismiss = { readingDialog = false },
        )
    }
    serviceFor?.let { item ->
        ServiceDialog(item, state.odometer?.miles, onSave = { d, m, n -> vm.logService(item.key, d, m, n); serviceFor = null }, onDismiss = { serviceFor = null })
    }
    intervalFor?.let { item ->
        IntervalDialog(item, onSave = { m, mo -> vm.updateInterval(item.key, m, mo); intervalFor = null }, onDismiss = { intervalFor = null })
    }
}

private fun parseDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim())
} catch (_: DateTimeParseException) {
    null
}

private fun parseMiles(text: String): Int? = text.filter(Char::isDigit).takeIf { it.isNotEmpty() && it.length <= 7 }?.toInt()

@Composable
private fun ReadingDialog(check: (Int, LocalDate) -> String?, onSave: (Int, LocalDate) -> Unit, onDismiss: () -> Unit) {
    var milesText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    val m = parseMiles(milesText)
    val d = parseDate(dateText)
    val problem = when {
        d == null -> "Date as YYYY-MM-DD"
        m == null -> null
        else -> check(m, d)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Odometer reading") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(milesText, { milesText = it }, label = { Text("Odometer (miles)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(dateText, { dateText = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(m!!, d!!) }, enabled = m != null && d != null && problem == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ServiceDialog(item: MaintenanceItemEntity, currentMiles: Int?, onSave: (LocalDate, Int?, String?) -> Unit, onDismiss: () -> Unit) {
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    var milesText by remember { mutableStateOf(currentMiles?.toString().orEmpty()) }
    var notes by remember { mutableStateOf("") }
    val d = parseDate(dateText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log: ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText("Enter when it was done. Leave miles empty if you don't know them.")
                OutlinedTextField(dateText, { dateText = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(milesText, { milesText = it }, label = { Text("Odometer (miles)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (shop, brand, cost…)") }, singleLine = true)
                if (d == null) Text("Date as YYYY-MM-DD", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(d!!, parseMiles(milesText), notes) }, enabled = d != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IntervalDialog(item: MaintenanceItemEntity, onSave: (Int?, Int?) -> Unit, onDismiss: () -> Unit) {
    var milesText by remember { mutableStateOf(item.intervalMiles?.toString().orEmpty()) }
    var monthsText by remember { mutableStateOf(item.intervalMonths?.toString().orEmpty()) }
    val months = monthsText.filter(Char::isDigit).takeIf { it.isNotEmpty() && it.length <= 3 }?.toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Interval: ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText("Whichever comes first. Leave one empty to use only the other. Check your owner's manual.")
                OutlinedTextField(milesText, { milesText = it }, label = { Text("Every … miles") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(monthsText, { monthsText = it }, label = { Text("Every … months") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parseMiles(milesText), months) }, enabled = parseMiles(milesText) != null || months != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
