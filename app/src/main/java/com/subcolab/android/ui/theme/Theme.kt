package com.subcolab.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.subcolab.android.model.ThemeMode

@Immutable
data class SubcolabPalette(
    val creamBg: Color,
    val cardSurface: Color,
    val cardSurfaceAlt: Color,
    val warmBorder: Color,
    val sagePrimary: Color,
    val sageContainer: Color,
    val skyAccent: Color,
    val skyContainer: Color,
    val skyTextStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val successGreen: Color,
    val dangerRed: Color,
)

private val LightPalette = SubcolabPalette(
    creamBg = Color(0xFFFAF7F0),
    cardSurface = Color(0xFFFFFFFF),
    cardSurfaceAlt = Color(0xFFF1ECE1),
    warmBorder = Color(0xFFE2DCCD),
    sagePrimary = Color(0xFF2E7D5B),
    sageContainer = Color(0xFFDCEFE5),
    skyAccent = Color(0xFF5B9BD5),
    skyContainer = Color(0xFFDDF0FB),
    skyTextStrong = Color(0xFF2B6CB0),
    textPrimary = Color(0xFF1C2621),
    textSecondary = Color(0xFF5A6860),
    successGreen = Color(0xFF16A34A),
    dangerRed = Color(0xFFDC2626),
)

private val DarkPalette = SubcolabPalette(
    creamBg = Color(0xFF0B0F17),
    cardSurface = Color(0xFF131A26),
    cardSurfaceAlt = Color(0xFF1B2434),
    warmBorder = Color(0xFF283449),
    sagePrimary = Color(0xFF2563EB),
    sageContainer = Color(0xFF172554),
    skyAccent = Color(0xFF38BDF8),
    skyContainer = Color(0xFF0C2A3E),
    skyTextStrong = Color(0xFF60A5FA),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    successGreen = Color(0xFF34D399),
    dangerRed = Color(0xFFF87171),
)

private val SoftDarkPalette = SubcolabPalette(
    creamBg = Color(0xFF1E222A),
    cardSurface = Color(0xFF272C36),
    cardSurfaceAlt = Color(0xFF313744),
    warmBorder = Color(0xFF3E4656),
    sagePrimary = Color(0xFF5B93F5),
    sageContainer = Color(0xFF25344E),
    skyAccent = Color(0xFF5B93F5),
    skyContainer = Color(0xFF233046),
    skyTextStrong = Color(0xFF8AB4F8),
    textPrimary = Color(0xFFE2E6EE),
    textSecondary = Color(0xFFA6B0C0),
    successGreen = Color(0xFF4ADE80),
    dangerRed = Color(0xFFE14D67),
)

private val LocalSubcolabPalette = staticCompositionLocalOf { SoftDarkPalette }

object SubcolabColors {
    val CreamBg: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.creamBg
    val CardSurface: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.cardSurface
    val CardSurfaceAlt: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.cardSurfaceAlt
    val WarmBorder: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.warmBorder

    val SagePrimary: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.sagePrimary
    val SageContainer: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.sageContainer
    val SkyAccent: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.skyAccent
    val SkyContainer: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.skyContainer
    val SkyTextStrong: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.skyTextStrong

    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.textSecondary
    val SuccessGreen: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.successGreen
    val DangerRed: Color @Composable @ReadOnlyComposable get() = LocalSubcolabPalette.current.dangerRed

    // Cinema Player Exception (always cinema-black + high-contrast yellow subtitles regardless of Light/Dark mode)
    val CinemaBackground = Color(0xFF000000)
    val CinemaSubtitleYellow = Color(0xFFFFFF00)
    val CinemaSkyBlue = Color(0xFF7CC4FA)

    // Preserved semantic aliases so all screens adapt seamlessly to Light & Dark mode
    val Obsidian: Color @Composable @ReadOnlyComposable get() = CreamBg
    val SurfaceDark: Color @Composable @ReadOnlyComposable get() = CardSurface
    val SurfaceElevated: Color @Composable @ReadOnlyComposable get() = CardSurfaceAlt
    val BorderSubtle: Color @Composable @ReadOnlyComposable get() = WarmBorder
    val SubtitleYellow: Color @Composable @ReadOnlyComposable get() = SagePrimary
    val SubtitleYellowDark: Color @Composable @ReadOnlyComposable get() = SageContainer
    val GpuCyan: Color @Composable @ReadOnlyComposable get() = SkyAccent
    val GpuCyanDark: Color @Composable @ReadOnlyComposable get() = SkyContainer
}

private val LightColorScheme = lightColorScheme(
    primary = LightPalette.sagePrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = LightPalette.sageContainer,
    onPrimaryContainer = LightPalette.sagePrimary,
    secondary = LightPalette.skyAccent,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = LightPalette.skyContainer,
    onSecondaryContainer = LightPalette.skyTextStrong,
    tertiary = LightPalette.successGreen,
    onTertiary = Color(0xFFFFFFFF),
    background = LightPalette.creamBg,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.cardSurface,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = LightPalette.cardSurfaceAlt,
    onSurfaceVariant = LightPalette.textSecondary,
    outline = LightPalette.warmBorder,
    error = LightPalette.dangerRed,
    onError = Color(0xFFFFFFFF),
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPalette.sagePrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = DarkPalette.sageContainer,
    onPrimaryContainer = DarkPalette.skyTextStrong,
    secondary = DarkPalette.skyAccent,
    onSecondary = Color(0xFF001F2E),
    secondaryContainer = DarkPalette.skyContainer,
    onSecondaryContainer = DarkPalette.skyAccent,
    tertiary = DarkPalette.successGreen,
    onTertiary = Color(0xFF002114),
    background = DarkPalette.creamBg,
    onBackground = DarkPalette.textPrimary,
    surface = DarkPalette.cardSurface,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.cardSurfaceAlt,
    onSurfaceVariant = DarkPalette.textSecondary,
    outline = DarkPalette.warmBorder,
    error = DarkPalette.dangerRed,
    onError = Color(0xFF1A0000),
)

private val SoftDarkColorScheme = darkColorScheme(
    primary = SoftDarkPalette.sagePrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = SoftDarkPalette.sageContainer,
    onPrimaryContainer = SoftDarkPalette.skyTextStrong,
    secondary = SoftDarkPalette.skyAccent,
    onSecondary = Color(0xFF0E1726),
    secondaryContainer = SoftDarkPalette.skyContainer,
    onSecondaryContainer = SoftDarkPalette.skyTextStrong,
    tertiary = SoftDarkPalette.successGreen,
    onTertiary = Color(0xFF0B2418),
    background = SoftDarkPalette.creamBg,
    onBackground = SoftDarkPalette.textPrimary,
    surface = SoftDarkPalette.cardSurface,
    onSurface = SoftDarkPalette.textPrimary,
    surfaceVariant = SoftDarkPalette.cardSurfaceAlt,
    onSurfaceVariant = SoftDarkPalette.textSecondary,
    outline = SoftDarkPalette.warmBorder,
    error = SoftDarkPalette.dangerRed,
    onError = Color(0xFFFFFFFF),
)

private fun buildTypography(palette: SubcolabPalette): Typography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = palette.textPrimary,
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.textPrimary,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.textPrimary,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = palette.textPrimary,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = palette.textSecondary,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
    ),
)

@Composable
fun isSubcolabDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SOFT_DARK -> true
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun SubcolabTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val palette = when (themeMode) {
        ThemeMode.SOFT_DARK -> SoftDarkPalette
        ThemeMode.LIGHT -> LightPalette
        ThemeMode.DARK -> DarkPalette
        ThemeMode.SYSTEM -> if (systemDark) DarkPalette else LightPalette
    }
    val colorScheme = when (themeMode) {
        ThemeMode.SOFT_DARK -> SoftDarkColorScheme
        ThemeMode.LIGHT -> LightColorScheme
        ThemeMode.DARK -> DarkColorScheme
        ThemeMode.SYSTEM -> if (systemDark) DarkColorScheme else LightColorScheme
    }

    CompositionLocalProvider(LocalSubcolabPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = buildTypography(palette),
            content = content,
        )
    }
}
