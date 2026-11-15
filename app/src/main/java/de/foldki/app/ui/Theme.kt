package de.foldki.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFC96442)

private val Light = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFAF9F5), onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFF0EEE6), onSurfaceVariant = Color(0xFF5E5D59),
    surfaceContainer = Color(0xFFF5F4EE),
    outline = Color(0xFFD9D6CC),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD97757), onPrimary = Color.White,
    background = Color(0xFF262624), onBackground = Color(0xFFF2F0E8),
    surface = Color(0xFF262624), onSurface = Color(0xFFF2F0E8),
    surfaceVariant = Color(0xFF30302E), onSurfaceVariant = Color(0xFFB8B5A9),
    surfaceContainer = Color(0xFF1F1E1D),
    outline = Color(0xFF4A4945),
    error = Color(0xFFF2B8B5),
)

@Composable
fun FoldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
