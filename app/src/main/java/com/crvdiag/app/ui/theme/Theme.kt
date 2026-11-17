package com.crvdiag.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Meaning-based colors used everywhere a state is shown (pills, icons, big values). */
enum class Status { Ok, Warning, Fault, Neutral, Busy }

@Immutable
data class StatusColors(val ok: Color, val warning: Color, val fault: Color, val neutral: Color, val busy: Color)

private val Palette = StatusColors(
    ok = Color(0xFF6DD58C),
    warning = Color(0xFFFFB86B),
    fault = Color(0xFFFF8A80),
    neutral = Color(0xFF9AA4B2),
    busy = Color(0xFF7CC4FF),
)

val LocalStatusColors = staticCompositionLocalOf { Palette }

@Composable
fun Status.color(): Color = LocalStatusColors.current.let {
    when (this) {
        Status.Ok -> it.ok
        Status.Warning -> it.warning
        Status.Fault -> it.fault
        Status.Neutral -> it.neutral
        Status.Busy -> it.busy
    }
}

/** Clean modern dark scheme: calm blue accent on near-black tonal surfaces. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC4FF),
    onPrimary = Color(0xFF00324F),
    primaryContainer = Color(0xFF004B72),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFFB8C8D9),
    onSecondary = Color(0xFF23323F),
    secondaryContainer = Color(0xFF38485A),
    onSecondaryContainer = Color(0xFFD4E4F6),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF4A2800),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    surfaceContainerLowest = Color(0xFF0A0C0F),
    surfaceContainerLow = Color(0xFF15181D),
    surfaceContainer = Color(0xFF1A1E24),
    surfaceContainerHigh = Color(0xFF242930),
    surfaceContainerHighest = Color(0xFF2F343B),
    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFF353A41),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF690005),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** App theme: dark only (CLAUDE.md: dark theme default; no light variant needed yet). */
@Composable
fun CrvDiagTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides Palette) {
        MaterialTheme(colorScheme = DarkColors, shapes = AppShapes, content = content)
    }
}
