package com.openscribe.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object OpenScribeColors {
    // Calm "Cream + Sage Green + Soft Sky Blue" Palette
    val CreamBg = Color(0xFFFAF7F0)
    val CardSurface = Color(0xFFFFFFFF)
    val CardSurfaceAlt = Color(0xFFF1ECE1)
    val WarmBorder = Color(0xFFE2DCCD)

    val SagePrimary = Color(0xFF2E7D5B)
    val SageContainer = Color(0xFFDCEFE5)
    val SkyAccent = Color(0xFF5B9BD5)
    val SkyContainer = Color(0xFFDDF0FB)
    val SkyTextStrong = Color(0xFF2B6CB0)

    val TextPrimary = Color(0xFF1C2621)
    val TextSecondary = Color(0xFF5A6860)
    val SuccessGreen = Color(0xFF16A34A)
    val DangerRed = Color(0xFFDC2626)

    // Cinema Player Exception (always dark background + high-contrast yellow subtitles)
    val CinemaBackground = Color(0xFF000000)
    val CinemaSubtitleYellow = Color(0xFFFFFF00)
    val CinemaSkyBlue = Color(0xFF7CC4FA)

    // Preserved semantic aliases so all existing screens adopt the Cream + Sage + Sky palette seamlessly
    val Obsidian = CreamBg
    val SurfaceDark = CardSurface
    val SurfaceElevated = CardSurfaceAlt
    val BorderSubtle = WarmBorder
    val SubtitleYellow = SagePrimary
    val SubtitleYellowDark = SageContainer
    val GpuCyan = SkyAccent
    val GpuCyanDark = SkyContainer
}

private val LightColorScheme = lightColorScheme(
    primary = OpenScribeColors.SagePrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = OpenScribeColors.SageContainer,
    onPrimaryContainer = OpenScribeColors.SagePrimary,
    secondary = OpenScribeColors.SkyAccent,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = OpenScribeColors.SkyContainer,
    onSecondaryContainer = OpenScribeColors.SkyTextStrong,
    tertiary = OpenScribeColors.SuccessGreen,
    onTertiary = Color(0xFFFFFFFF),
    background = OpenScribeColors.CreamBg,
    onBackground = OpenScribeColors.TextPrimary,
    surface = OpenScribeColors.CardSurface,
    onSurface = OpenScribeColors.TextPrimary,
    surfaceVariant = OpenScribeColors.CardSurfaceAlt,
    onSurfaceVariant = OpenScribeColors.TextSecondary,
    outline = OpenScribeColors.WarmBorder,
    error = OpenScribeColors.DangerRed,
    onError = Color(0xFFFFFFFF),
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
        colorScheme = LightColorScheme,
        typography = OpenScribeTypography,
        content = content,
    )
}
