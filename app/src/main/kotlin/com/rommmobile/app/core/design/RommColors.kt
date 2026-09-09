package com.rommmobile.app.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Exact RomM web tokens (frontend/src/styles/themes.ts). `toplayer` has no Material 3
 * counterpart, so the whole palette is exposed through [LocalRommColors] next to the
 * regular [ColorScheme].
 */
@Immutable
data class RommColors(
    val isDark: Boolean,
    val primary: Color,
    val primaryLighten: Color,
    val primaryDarken: Color,
    val secondary: Color,
    val secondaryLighten: Color,
    val secondaryDarken: Color,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val toplayer: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val red: Color = Color(0xFFDA3633),
    val green: Color = Color(0xFF3FB950),
    val blue: Color = Color(0xFF0070F3),
    val white: Color = Color(0xFFFEFDFE),
    val gray: Color = Color(0xFF5D5D5D),
    val black: Color = Color(0xFF000000),
    val gold: Color = Color(0xFFFFD700),
    /** Black at 50%: the RomM `translucent` band under text drawn over covers. */
    val translucent: Color = Color(0x80000000),
) {
    fun toColorScheme(): ColorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = white,
            primaryContainer = primaryDarken,
            onPrimaryContainer = secondaryLighten,
            inversePrimary = primaryLighten,
            secondary = secondary,
            onSecondary = background,
            secondaryContainer = secondaryDarken,
            onSecondaryContainer = secondaryLighten,
            tertiary = accent,
            onTertiary = background,
            tertiaryContainer = accent.copy(alpha = 0.25f),
            onTertiaryContainer = accent,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = toplayer,
            onSurfaceVariant = onSurfaceMuted,
            surfaceTint = primary,
            surfaceContainerLowest = background,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            surfaceContainerHigh = toplayer,
            surfaceContainerHighest = toplayer,
            surfaceBright = toplayer,
            surfaceDim = background,
            inverseSurface = white,
            inverseOnSurface = background,
            error = red,
            onError = white,
            errorContainer = red.copy(alpha = 0.25f),
            onErrorContainer = Color(0xFFFFB4AB),
            outline = gray,
            outlineVariant = gray.copy(alpha = 0.5f),
            scrim = black,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = white,
            primaryContainer = primaryLighten,
            onPrimaryContainer = white,
            inversePrimary = primaryLighten,
            secondary = secondary,
            onSecondary = white,
            secondaryContainer = secondaryLighten,
            onSecondaryContainer = primary,
            tertiary = accent,
            onTertiary = black,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = toplayer,
            onSurfaceVariant = onSurfaceMuted,
            surfaceTint = primary,
            surfaceContainerLowest = surface,
            surfaceContainerLow = surface,
            surfaceContainer = background,
            surfaceContainerHigh = toplayer,
            surfaceContainerHighest = toplayer,
            error = red,
            onError = white,
            outline = gray,
            outlineVariant = gray.copy(alpha = 0.35f),
            scrim = black,
        )
    }
}

val DarkRommColors = RommColors(
    isDark = true,
    primary = Color(0xFF8B74E8),
    primaryLighten = Color(0xFFA18FFF),
    primaryDarken = Color(0xFF6043C8),
    secondary = Color(0xFF9E8CD6),
    secondaryLighten = Color(0xFFEBE7FA),
    secondaryDarken = Color(0xFF7A6BB4),
    accent = Color(0xFFE1A38D),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    toplayer = Color(0xFF1C2330),
    onBackground = Color(0xFFFEFDFE),
    onSurface = Color(0xFFFEFDFE),
    onSurfaceMuted = Color(0xB3FEFDFE),
)

val LightRommColors = RommColors(
    isDark = false,
    primary = Color(0xFF371F69),
    primaryLighten = Color(0xFF7850E6),
    primaryDarken = Color(0xFF452788),
    secondary = Color(0xFF553E98),
    secondaryLighten = Color(0xFFEBE7FA),
    secondaryDarken = Color(0xFF3F2C78),
    accent = Color(0xFFE1A38D),
    background = Color(0xFFF2F4F8),
    surface = Color(0xFFFFFFFF),
    toplayer = Color(0xFFE4E9F0),
    onBackground = Color(0xFF0D1117),
    onSurface = Color(0xFF0D1117),
    onSurfaceMuted = Color(0xB30D1117),
)

val LocalRommColors = staticCompositionLocalOf { DarkRommColors }
