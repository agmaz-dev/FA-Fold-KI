package de.foldki.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.foldki.app.R

/** Design tokens of the Fold KI design (dark, warm grey + orange). Same values as the approved mock-ups. */
object FK {
    val Bg = Color(0xFF1C1B1A)
    val Surface = Color(0xFF262524)
    val Surface2 = Color(0xFF2F2D2B)
    val Line = Color(0xFF3A3734)
    val Text = Color(0xFFF2EEE9)
    val Muted = Color(0xFFA8A29B)
    val Dim = Color(0xFF77716A)
    val Accent = Color(0xFFE07A52)
    val AccentSoft = Color(0x24E07A52) // 14 %
    val OnAccent = Color(0xFF1C1B1A)
    val Ok = Color(0xFF6FCF8A)
    val OkSoft = Color(0x266FCF8A)
    val Side = Color(0xFF181716)
    val Scrim = Color(0x8C000000)

    // Icon tiles (background, symbol)
    val T1 = Color(0xFF3B2A22) to Accent
    val T2 = Color(0xFF22313B) to Color(0xFF7FB6E0)
    val T3 = Color(0xFF2A3523) to Color(0xFF9FCF72)
    val T4 = Color(0xFF352A3B) to Color(0xFFC49BE0)
    val T5 = Color(0xFF3B3522) to Color(0xFFE8C45A)
    val T6 = Surface2 to Muted
}

val Accent = FK.Accent

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val Scheme = darkColorScheme(
    primary = FK.Accent, onPrimary = FK.OnAccent,
    secondary = FK.Accent, onSecondary = FK.OnAccent,
    background = FK.Bg, onBackground = FK.Text,
    surface = FK.Bg, onSurface = FK.Text,
    surfaceVariant = FK.Surface, onSurfaceVariant = FK.Muted,
    surfaceContainer = FK.Side, surfaceContainerHigh = FK.Surface, surfaceContainerHighest = FK.Surface2,
    surfaceContainerLow = FK.Surface, surfaceContainerLowest = FK.Bg,
    outline = FK.Line, outlineVariant = FK.Line,
    error = Color(0xFFF2B8B5),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = Inter)
    return Typography(
        displayLarge = base.displayLarge.inter(), displayMedium = base.displayMedium.inter(), displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(), headlineMedium = base.headlineMedium.inter(), headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(), titleMedium = base.titleMedium.inter(), titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter().copy(fontSize = 15.sp), bodyMedium = base.bodyMedium.inter(), bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(), labelMedium = base.labelMedium.inter(), labelSmall = base.labelSmall.inter(),
    )
}

/** Always the dark Fold KI design (the approved look), independent of the system setting. */
@Composable
fun FoldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = typography(), content = content)
}
