package com.akashic.mobile.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private data class ThemeColors(
    val dark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val borderStrong: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val warning: Color,
    val success: Color,
    val trace: Color,
    val traceText: Color,
    val traceContainer: Color,
)

private val LightTheme = ThemeColors(
    dark = false,
    canvas = Color(0xFFF3F7FC), surface = Color(0xFFFFFFFF),
    surfaceLow = Color(0xFFEBF1F8), surfaceHigh = Color(0xFFD4DDEA),
    textPrimary = Color(0xFF20222C), textSecondary = Color(0xFF545766), textMuted = Color(0xFF747887),
    border = Color(0xFFC7CAD7), borderStrong = Color(0xFF979BA9),
    primary = Color(0xFF2D72C4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCBE0FC), onPrimaryContainer = Color(0xFF0B376A),
    error = Color(0xFFBE241F), errorContainer = Color(0xFFFFDDD7),
    warning = Color(0xFF9F5D00), success = Color(0xFF237A4B), trace = Color(0xFF9B5FED),
    traceText = Color(0xFF7643BD), traceContainer = Color(0xFFE5DBFC),
)

private val DarkTheme = ThemeColors(
    dark = true,
    canvas = Color(0xFF11151C), surface = Color(0xFF181D26),
    surfaceLow = Color(0xFF202733), surfaceHigh = Color(0xFF2B3442),
    textPrimary = Color(0xFFEBEEF5), textSecondary = Color(0xFFB8BFCC), textMuted = Color(0xFF858E9F),
    border = Color(0xFF3A4453), borderStrong = Color(0xFF596577),
    primary = Color(0xFF8FC2FF), onPrimary = Color(0xFF082A50),
    primaryContainer = Color(0xFF294E78), onPrimaryContainer = Color(0xFFD3E7FF),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF8C1D18),
    warning = Color(0xFFFFB95C), success = Color(0xFF77DBA4), trace = Color(0xFFCBB2FF),
    traceText = Color(0xFFD8C5FF), traceContainer = Color(0xFF49346F),
)

private val WarmPaperTheme = ThemeColors(
    dark = false,
    canvas = Color(0xFFF8F1E5), surface = Color(0xFFFFF9F0),
    surfaceLow = Color(0xFFF1E5D4), surfaceHigh = Color(0xFFE6D5BE),
    textPrimary = Color(0xFF312B25), textSecondary = Color(0xFF62584E), textMuted = Color(0xFF85786B),
    border = Color(0xFFD4C2AA), borderStrong = Color(0xFFAB9478),
    primary = Color(0xFF986229), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFECD2AE), onPrimaryContainer = Color(0xFF4A2B0C),
    error = Color(0xFFA83B32), errorContainer = Color(0xFFF8D8D2),
    warning = Color(0xFF8A5B12), success = Color(0xFF39704D), trace = Color(0xFF8059A8),
    traceText = Color(0xFF69418F), traceContainer = Color(0xFFEADCF4),
)

private fun ThemeColors.toColorScheme(): ColorScheme {
    val common = if (dark) darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = trace,
        onSecondary = onPrimary,
        secondaryContainer = traceContainer,
        onSecondaryContainer = traceText,
        tertiary = warning,
        onTertiary = onPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceLow,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
        background = canvas,
        onBackground = textPrimary,
        outline = border,
        outlineVariant = borderStrong,
        error = error,
        onError = onPrimary,
        errorContainer = errorContainer,
        onErrorContainer = textPrimary,
        inversePrimary = success,
    ) else lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = trace,
        onSecondary = onPrimary,
        secondaryContainer = traceContainer,
        onSecondaryContainer = traceText,
        tertiary = warning,
        onTertiary = onPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceLow,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
        background = canvas,
        onBackground = textPrimary,
        outline = border,
        outlineVariant = borderStrong,
        error = error,
        onError = onPrimary,
        errorContainer = errorContainer,
        onErrorContainer = textPrimary,
        inversePrimary = success,
    )
    return common
}

private val ThemeSchemes = mapOf(
    "light" to LightTheme.toColorScheme(),
    "dark" to DarkTheme.toColorScheme(),
    "warm-paper" to WarmPaperTheme.toColorScheme(),
)

private val AkashicShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AkashicTheme(
    themeId: String = "light",
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ThemeSchemes[themeId] ?: ThemeSchemes.getValue("light"),
        shapes = AkashicShapes,
        content = content,
    )
}
