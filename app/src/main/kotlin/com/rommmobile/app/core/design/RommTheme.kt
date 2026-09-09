package com.rommmobile.app.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** Extra safe margin (0-16 dp) for rounded corners / TV overscan, chosen in settings. */
val LocalScreenMargin = staticCompositionLocalOf { 0.dp }

object RommTheme {
    val colors: RommColors
        @Composable @ReadOnlyComposable get() = LocalRommColors.current
    val layout: LayoutClass
        @Composable @ReadOnlyComposable get() = LocalLayoutClass.current
}

@Composable
fun RommTheme(
    themeMode: ThemeMode,
    screenMarginDp: Int = 0,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkRommColors else LightRommColors
    val layout = rememberLayoutClass()
    val margin: Dp = (screenMarginDp.coerceIn(0, 16)).dp + if (layout.isTv) layout.overscanInset else 0.dp
    CompositionLocalProvider(
        LocalRommColors provides colors,
        LocalLayoutClass provides layout,
        LocalScreenMargin provides margin,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = rommTypography(layout),
            shapes = RommShapes,
        ) {
            // Material pads every Button and IconButton out to a 48 dp touch target while drawing
            // the control smaller inside it. The focus ring is drawn on the node, so it followed
            // that invisible box and came out a different shape from the button. This app's
            // controls are already large enough to hit, so the padding goes.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            // Without a Surface at the root, LocalContentColor stays at its Compose default
            // (black) and every Text that does not name a colour is drawn black on the dark
            // background. This one wrapper is what makes body text readable in both themes.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.background,
                contentColor = colors.onBackground,
                content = content,
            )
            }
        }
    }
}

/** RomM uses Roboto, which is the Android system font: no custom typeface shipped. */
private fun rommTypography(layout: LayoutClass): Typography {
    val titleSize = if (layout.isShort) 18.sp else 20.sp
    return Typography(
        titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = titleSize, lineHeight = (titleSize.value + 6).sp),
        titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
        bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
        headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp),
        displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp),
    )
}

/** Radii from the design doc: chips 6, covers 8, platform cards 12, dialogs 16. */
val RommShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

val PillShape = RoundedCornerShape(999.dp)
