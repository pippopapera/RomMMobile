package com.rommmobile.app.core.design

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }
enum class HeightClass { SHORT, TALL }
enum class ScreenShape { PORTRAIT, SQUARE, LANDSCAPE }

/** Where the primary navigation lives, and how wide it is. */
enum class NavMode(val railWidth: Dp, val showLabels: Boolean) {
    BOTTOM_BAR(0.dp, true),
    RAIL_COMPACT(56.dp, false),
    RAIL(80.dp, true),
    RAIL_TV(96.dp, true),
}

/**
 * Two-axis size class. Width alone is useless on handhelds: a Retroid Pocket Classic is
 * 472x411 dp (compact width, ridiculous height) and an RG Cube is a 360 dp square.
 */
@Immutable
data class LayoutClass(
    val widthDp: Int,
    val heightDp: Int,
    val isTv: Boolean,
    val fontScale: Float,
) {
    val widthClass: WidthClass = when {
        widthDp < 600 -> WidthClass.COMPACT
        widthDp < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }
    val heightClass: HeightClass = if (heightDp < 480) HeightClass.SHORT else HeightClass.TALL
    val shape: ScreenShape = run {
        val ratio = widthDp.toFloat() / heightDp.coerceAtLeast(1)
        when {
            ratio < 0.9f -> ScreenShape.PORTRAIT
            ratio <= 1.25f -> ScreenShape.SQUARE
            else -> ScreenShape.LANDSCAPE
        }
    }
    val isShort: Boolean get() = heightClass == HeightClass.SHORT
    val isCompact: Boolean get() = widthClass == WidthClass.COMPACT

    val navMode: NavMode = when {
        isTv -> NavMode.RAIL_TV
        heightClass == HeightClass.SHORT -> NavMode.RAIL_COMPACT
        widthClass == WidthClass.COMPACT -> NavMode.BOTTOM_BAR
        else -> NavMode.RAIL
    }
    val bottomBarHeight: Dp = 72.dp
    val topBarHeight: Dp = when {
        isTv -> 48.dp
        isShort -> 40.dp
        else -> 56.dp
    }
    val padding: Dp = if (isShort) 8.dp else 12.dp
    val gap: Dp = if (isShort) 6.dp else 8.dp

    /** Minimum cell for cover grids; the AlphabetRail width is subtracted by the caller. */
    val coverMinCell: Dp = run {
        val base = when {
            isTv || widthDp >= 840 -> 148
            widthDp >= 600 -> 128
            widthDp >= 400 -> 116
            else -> 104
        }
        (if (isShort) base - 8 else base).dp
    }
    val platformMinCell: Dp = coverMinCell + 16.dp
    val twoPane: Boolean = widthClass == WidthClass.EXPANDED && shape == ScreenShape.LANDSCAPE
    val alphabetRailWidth: Dp = if (widthDp >= 600) 32.dp else 28.dp
    val focusScale: Float = if (isTv) 1.10f else 1.07f
    val overscanInset: Dp = if (isTv) (minOf(widthDp, heightDp) * 0.05f).dp else 0.dp
    val miniBarHeight: Dp = 44.dp
    val listRowHeight: Dp = 56.dp
    val maxColumns: Int = 8
}

val LocalLayoutClass = staticCompositionLocalOf { LayoutClass(393, 873, isTv = false, fontScale = 1f) }

@Composable
fun rememberLayoutClass(): LayoutClass {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val container = LocalWindowInfo.current.containerSize
    val isTv = remember(context) { context.isTelevision() }
    val widthDp = if (container.width > 0) (container.width / density.density).roundToInt() else configuration.screenWidthDp
    val heightDp = if (container.height > 0) (container.height / density.density).roundToInt() else configuration.screenHeightDp
    return remember(widthDp, heightDp, isTv, configuration.fontScale) {
        LayoutClass(widthDp = widthDp, heightDp = heightDp, isTv = isTv, fontScale = configuration.fontScale)
    }
}

fun Context.isTelevision(): Boolean {
    val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
