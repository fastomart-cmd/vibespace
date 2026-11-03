package com.vibespace.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object VibeColors {
    val Black = Color(0xFF07040D)
    val DeepPurple = Color(0xFF1A0B2E)
    val Surface = Color(0xFF130A20)
    val SurfaceHigh = Color(0xFF21113A)
    val Outline = Color(0xFF3A2560)
    val NeonPurple = Color(0xFFB05CFF)
    val NeonCyan = Color(0xFF00E5FF)
    val TextPrimary = Color(0xFFF3ECFF)
    val TextSecondary = Color(0xFFB7A8D1)
    val Error = Color(0xFFFF5C8A)

    val brandGradient = Brush.linearGradient(listOf(NeonPurple, Color(0xFF6A2BD9), NeonCyan))
    val backgroundGradient = Brush.verticalGradient(listOf(DeepPurple, Black, Black))
}

private val scheme = darkColorScheme(
    primary = VibeColors.NeonPurple,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3B1866),
    onPrimaryContainer = VibeColors.TextPrimary,
    secondary = VibeColors.NeonCyan,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF00363D),
    onSecondaryContainer = Color(0xFFB8F6FF),
    tertiary = VibeColors.NeonCyan,
    background = VibeColors.Black,
    onBackground = VibeColors.TextPrimary,
    surface = VibeColors.Surface,
    onSurface = VibeColors.TextPrimary,
    surfaceVariant = VibeColors.SurfaceHigh,
    onSurfaceVariant = VibeColors.TextSecondary,
    surfaceContainer = VibeColors.Surface,
    surfaceContainerHigh = VibeColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF2A1748),
    surfaceContainerLow = Color(0xFF0F0819),
    outline = VibeColors.Outline,
    outlineVariant = Color(0xFF2A1A45),
    error = VibeColors.Error,
    onError = Color.Black,
)

private val typography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val BrandWordmark = TextStyle(fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 0.5.sp)

@Composable
fun VibeSpaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
