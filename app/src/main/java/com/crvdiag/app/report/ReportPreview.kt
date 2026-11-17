package com.crvdiag.app.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color

@Composable
private fun Tone.ui(): Color = when (this) {
    Tone.Normal -> MaterialTheme.colorScheme.onSurface
    Tone.Good -> Status.Ok.color()
    Tone.Warn -> Status.Warning.color()
    Tone.Bad -> Status.Fault.color()
    Tone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** On-screen rendering of the same blocks the PDF contains. */
@Composable
fun ReportPreview(blocks: List<Block>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Title -> Column {
                    Text(b.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(b.subtitle, color = Tone.Muted.ui(), style = MaterialTheme.typography.bodyMedium)
                }
                is Block.Heading -> Text(b.text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                is Block.Text -> Text(b.text, color = b.tone.ui(), style = MaterialTheme.typography.bodyMedium)
                is Block.Fields -> b.rows.forEach { f ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(f.label, Modifier.weight(0.36f), color = Tone.Muted.ui(), style = MaterialTheme.typography.bodyMedium)
                        Text(f.value, Modifier.weight(0.64f), color = f.tone.ui(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is Block.Table -> Column {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(6.dp)) {
                        b.headers.forEachIndexed { i, h -> Text(h, Modifier.weight(b.weights[i]), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge) }
                    }
                    b.rows.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(6.dp)) {
                            r.cells.forEachIndexed { i, c -> Text(c, Modifier.weight(b.weights[i]), color = r.tone.ui(), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}
