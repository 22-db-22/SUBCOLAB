package com.openscribe.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object OpenScribeColors {
    val Obsidian = Color(0xFF0B0F17)
    val SurfaceDark = Color(0xFF131A26)
    val SurfaceElevated = Color(0xFF1B2434)
    val BorderSubtle = Color(0xFF283449)
    val SubtitleYellow = Color(0xFFFBBF24)
    val SubtitleYellowDark = Color(0xFF423007)
    val GpuCyan = Color(0xFF38BDF8)
    val GpuCyanDark = Color(0xFF0A2E40)
    val SuccessGreen = Color(0xFF34D399)
    val DangerRed = Color(0xFFF87171)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
}

private val DarkColorScheme = darkColorScheme(
    primary = OpenScribeColors.SubtitleYellow,
    onPrimary = Color(0xFF1A1200),
    primaryContainer = OpenScribeColors.SubtitleYellowDark,
    onPrimaryContainer = OpenScribeColors.SubtitleYellow,
    secondary = OpenScribeColors.GpuCyan,
    onSecondary = Color(0xFF001F2E),
    secondaryContainer = OpenScribeColors.GpuCyanDark,
    onSecondaryContainer = OpenScribeColors.GpuCyan,
    tertiary = OpenScribeColors.SuccessGreen,
    background = OpenScribeColors.Obsidian,
    onBackground = OpenScribeColors.TextPrimary,
    surface = OpenScribeColors.SurfaceDark,
    onSurface = OpenScribeColors.TextPrimary,
    surfaceVariant = OpenScribeColors.SurfaceElevated,
    onSurfaceVariant = OpenScribeColors.TextSecondary,
    outline = OpenScribeColors.BorderSubtle,
    error = OpenScribeColors.DangerRed,
)

private val OpenScribeTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = OpenScribeColors.TextPrimary,
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        color = OpenScribeColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = OpenScribeColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = OpenScribeColors.TextPrimary,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = OpenScribeColors.TextSecondary,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
    ),
)

@Composable
fun OpenScribeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = OpenScribeTypography,
        content = content,
    )
}
