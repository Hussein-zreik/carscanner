package com.crvdiag.app.tests

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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.components.BigValue
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.components.StepRow
import com.crvdiag.app.ui.icons.AppIcons
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color
import com.crvdiag.obd.DiagFinding
import com.crvdiag.obd.Finding

/** Hub listing every guided test. [onOpen] receives null for the misfire check. */
@Composable
fun TestsHubScreen(onOpenMisfire: () -> Unit, onOpen: (TestKind) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("Tests", "Guided, read-only checks for your CR-V. Each one explains what it found in plain English.")
        val cards = listOf<Triple<String, String, () -> Unit>>(
            Triple("Misfire check", "Is the rough idle a misfire, and which cylinder?", onOpenMisfire),
        ) + TestKind.entries.map { Triple(it.title, it.summary) { onOpen(it) } }
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { (title, summary, open) ->
                    SectionCard(title, Modifier.weight(1f), icon = AppIcons.Spark) {
                        MutedText(summary)
                        Button(onClick = open) { Text("Open") }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        MutedText("All tests are for a parked car and stop by themselves if the car moves. A guide, not a diagnosis: confirm repairs with a shop.")
    }
}

@Composable
fun GuidedTestScreen(kind: TestKind, vm: TestsViewModel, onStart: () -> Unit, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val active = s.kind == kind
    val phases = TestPlans.phases(kind)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(kind.title, kind.summary) {
            TextButton(onClick = onBack) { Text("All tests") }
            when {
                active && s.running -> OutlinedButton(onClick = vm::cancel) { Text("Stop") }
                else -> Button(onClick = onStart) { Text(if (active && s.verdict != null) "Run again" else "Start test") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionCard("Steps", Modifier.weight(1f), icon = AppIcons.Spark) {
                phases.forEachIndexed { i, p ->
                    val status = when {
                        !active -> Status.Neutral
                        i < s.doneSteps -> Status.Ok
                        i == s.phaseIndex && (s.measuring || s.waitingForUser) -> Status.Busy
                        else -> Status.Neutral
                    }
                    StepRow(p.title, if (active && i == s.phaseIndex && (s.measuring || s.waitingForUser)) p.instruction else null, status)
                }
                if (active && s.waitingForUser) {
                    val p = phases[s.phaseIndex]
                    Text(p.instruction, fontWeight = FontWeight.SemiBold)
                    Button(onClick = vm::next, modifier = Modifier.fillMaxWidth()) { Text("Start: ${p.title}") }
                }
                if (active && s.measuring) {
                    val p = phases[s.phaseIndex]
                    if (p.seconds > 0) {
                        BigValue("${s.secondsLeft} s")
                        LinearProgressIndicator(progress = { 1f - s.secondsLeft.toFloat() / p.seconds }, modifier = Modifier.fillMaxWidth())
                    }
                    s.live?.let { MutedText(it) }
                }
                if (!active) MutedText("Tap Start test. Each step tells you exactly what to do.")
                if (active) s.message?.let { Text(it, color = Status.Warning.color()) }
            }
            SectionCard("Result", Modifier.weight(1f), icon = Icons.Filled.CheckCircle) {
                val v = s.verdict.takeIf { active }
                if (v == null) MutedText("The result appears here when all steps are done.")
                else {
                    BigValue(v.headline, when { v.needsAttention -> Status.Warning; v.pending -> Status.Neutral; else -> Status.Ok })
                    v.findings.forEach { FindingRow(it) }
                    MutedText("A guide, not a diagnosis.")
                }
            }
        }
        if (active && kind == TestKind.SelfTests && s.selfTests.isNotEmpty()) {
            SectionCard("All stored self-tests", Modifier.fillMaxWidth(), icon = Icons.Filled.Info) {
                MutedText("Raw values against the car's own limits. \"Not run yet\" means the test hasn't run since codes were cleared.")
                s.selfTests.forEach { m ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, Modifier.weight(0.45f))
                        Text(m.tests.filterNot(com.crvdiag.obd.Mode06Catalog::notRun).joinToString { "${it.value} (${it.min}–${it.max})" }.ifEmpty { "—" },
                            Modifier.weight(0.35f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(m.status, Modifier.weight(0.2f), color = when (m.status) {
                            "Passed" -> Status.Ok; "FAILED" -> Status.Fault; else -> Status.Neutral
                        }.color())
                    }
                }
            }
        }
    }
}

@Composable
fun FindingRow(f: DiagFinding) {
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
