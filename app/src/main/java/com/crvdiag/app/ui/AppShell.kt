package com.crvdiag.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.icons.AppIcons
import com.crvdiag.app.ui.theme.Status
import com.crvdiag.app.ui.theme.color

enum class Destination(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Home),
    Codes("Codes", Icons.Filled.Warning),
    Live("Live data", AppIcons.Gauge),
    Connection("Connection", AppIcons.Plug),
    Advanced("Advanced", Icons.Filled.Build),
}

/** One-word connection state for the narrow rail. */
fun railLabel(status: Status): String = when (status) {
    Status.Ok -> "Online"
    Status.Busy -> "Linking"
    Status.Fault, Status.Warning -> "Error"
    Status.Neutral -> "Offline"
}

/** Landscape tablet layout: navigation rail on the left, screen content on the right. */
@Composable
fun AppShell(
    current: Destination,
    onNavigate: (Destination) -> Unit,
    linkStatus: Status,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            header = {
                Icon(
                    AppIcons.Gauge, contentDescription = "CR-V Diag",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp).size(34.dp),
                )
            },
        ) {
            Spacer(Modifier.height(12.dp))
            Destination.entries.forEach { d ->
                NavigationRailItem(
                    selected = d == current,
                    onClick = { onNavigate(d) },
                    icon = { Icon(d.icon, contentDescription = null) },
                    label = { Text(d.label) },
                )
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(10.dp).background(linkStatus.color(), CircleShape))
                Text(railLabel(linkStatus), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Surface provides the light content colour for text outside cards (headers, notes).
        Surface(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
            color = MaterialTheme.colorScheme.background,
        ) { content() }
    }
}
