package com.rommmobile.app.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape

/**
 * Gamepad focus for CARDS and rail items: a short scale-up and nothing else. No ring, no glow -
 * the owner's call: a glow around every cover and every rail entry made the whole screen busy,
 * while a card that grows when you land on it is understood without being looked at. The ring
 * lives on [gamepadFocusRing], for buttons. Place BEFORE the focusable modifier (clickable /
 * focusable) in the chain so [onFocusChanged] observes that target.
 *
 * The scale runs in the draw phase via graphicsLayer, so neighbours never relayout.
 */
fun Modifier.gamepadFocus(
    shape: Shape,
    scale: Float? = null,
    ringWidthDp: Int = 2,
    onFocused: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val layout = LocalLayoutClass.current
    var focused by remember { mutableStateOf(false) }
    val target = scale ?: layout.focusScale
    val t by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "focus",
    )
    this
        .zIndex(if (focused) 1f else 0f)
        .onFocusChanged {
            if (focused != it.isFocused) {
                focused = it.isFocused
                onFocused?.invoke(it.isFocused)
            }
        }
        .graphicsLayer {
            val s = 1f + (target - 1f) * t
            scaleX = s
            scaleY = s
        }
}

/**
 * Buttons and chips. Same two signals as [gamepadFocus]: it grows a little and it gets a violet
 * edge. Nothing else - a cast shadow spilling out past the control read as a smudge rather than
 * as "you are here".
 */
fun Modifier.gamepadFocusRing(shape: Shape, ringWidthDp: Int = 2): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val t by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "focusPop",
    )
    this
        .zIndex(if (focused) 1f else 0f)
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            val s = 1f + 0.06f * t
            scaleX = s
            scaleY = s
        }
        .focusGlow(shape, t)
}

/**
 * The one focus decoration in the app, drawn OUTSIDE the control with a small gap: a white line
 * that follows the control's own shape, wrapped in two fading violet passes that read as a glow.
 *
 * Outside and white on purpose. A line on the edge in the button's own violet vanished on the
 * violet buttons, which is exactly where the user most needs to see it; a white ring with a gap
 * between it and the control reads on a violet fill, on a dark card and on the rail alike.
 */
private fun Modifier.focusGlow(shape: Shape, t: Float): Modifier = composed {
    val colors = LocalRommColors.current
    drawWithContent {
        drawContent()
        if (t <= 0f) return@drawWithContent
        val gap = 3.dp.toPx()
        val line = 2.dp.toPx()
        // Each pass is inset by a negative amount so `size` inside it is the enlarged box and the
        // outline keeps the control's proportions instead of being a bigger blob around it.
        fun ring(offset: Float, width: Float, color: Color) {
            inset(-offset) {
                drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(width))
            }
        }
        ring(gap + line + 6.dp.toPx(), 8.dp.toPx(), colors.primaryLighten.copy(alpha = 0.10f * t))
        ring(gap + line + 2.dp.toPx(), 4.dp.toPx(), colors.primaryLighten.copy(alpha = 0.28f * t))
        ring(gap + line / 2f, line, colors.white.copy(alpha = 0.95f * t))
    }
}

/**
 * Top-bar icons (back arrow, view toggle, sort, refresh, ...): a short scale-up and nothing else.
 * A ring around a 24 dp glyph in the header read as noise next to the title; growing is enough.
 */
fun Modifier.gamepadFocusIcon(scale: Float = 1.22f): Modifier = gamepadFocus(CircleShape, scale = scale)

/**
 * Full-width list rows. Growing a row that already spans its pane only pushes its left edge under
 * the screen border, so focus is shown as a fill plus a leading bar and nothing moves.
 */
fun Modifier.gamepadFocusRow(shape: Shape): Modifier = composed {
    val colors = LocalRommColors.current
    var focused by remember { mutableStateOf(false) }
    val t by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "rowFocus",
    )
    this
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .drawBehind {
            if (t <= 0f) return@drawBehind
            drawRect(colors.primary.copy(alpha = 0.20f * t))
            drawRect(colors.primaryLighten.copy(alpha = t), size = Size(3.dp.toPx(), size.height))
        }
}

/**
 * Single-line text fields swallow D-pad up/down (caret navigation), trapping gamepad users
 * inside the field. This moves focus out instead, and draws the usual ring.
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.gamepadTextField(shape: Shape, ringWidthDp: Int = 2, onDismissed: (() -> Unit)? = null): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    this
        // Before the IME sees it, or the keyboard swallows B and the field keeps focus and simply
        // reopens it. Consumed ONLY while the keyboard is up: with it down, B has to stay a
        // normal back, or a dialog holding a text field can never be closed from the pad. And
        // focus is cleared, not moved, because moving it into a neighbouring field just pops the
        // keyboard straight back open.
        .onInterceptKeyBeforeSoftKeyboard { event ->
            if (event.key != Key.ButtonB && event.key != Key.Back) return@onInterceptKeyBeforeSoftKeyboard false
            if (!imeVisible) return@onInterceptKeyBeforeSoftKeyboard false
            if (event.type == KeyEventType.KeyDown) {
                keyboard?.hide()
                focusManager.clearFocus()
                // The owner may want to send the pad somewhere now that focus sits on nothing.
                onDismissed?.invoke()
            }
            true
        }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                else -> false
            }
        }
    // No ring here: the field already answers focus with its own coloured border (Material
    // OutlinedTextField), and a second outline around a search box was one too many.
}
