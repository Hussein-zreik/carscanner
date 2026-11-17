package com.crvdiag.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFFB0BEC5),
    tertiary = Color(0xFFFFB74D),
    error = Color(0xFFF28B82),
    background = Color(0xFF0E1114),
    surface = Color(0xFF161A1E),
)

private val LightColors = lightColorScheme()

/** App theme. Dark is the default; light is kept only for an explicit future setting. */
@Composable
fun CrvDiagTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
