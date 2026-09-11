package com.rommmobile.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommTheme
import kotlinx.coroutines.delay

/**
 * The web app's vertical letter bar, fed by the server's `char_index`. Letters without
 * results are dimmed and skipped by trigger navigation. Touch: tap or drag continuously.
 */
@Composable
fun AlphabetRail(
    letters: List<LetterEntry>,
    current: String?,
    onJump: (LetterEntry) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = RommTheme.layout.alphabetRailWidth,
) {
    val colors = RommTheme.colors
    val density = LocalDensity.current
    val jump by rememberUpdatedState(onJump)

    BoxWithConstraints(modifier.width(width).fillMaxHeight()) {
        val slot = 14.dp
        val maxLetters = (maxHeight / slot).toInt().coerceAtLeast(6)
        val visible = remember(letters, maxLetters) { thin(letters, maxLetters) }
        var railFocused by remember { mutableStateOf(false) }
        var dragLabel by remember { mutableStateOf<String?>(null) }
        // A finger scrubbing the rail crosses ten letters in a blink. Jumping on each one asked
        // the server for a page per letter and chained Paging refreshes faster than any could
        // finish; the grid was left on skeletons. The jump waits for the finger to rest.
        var pendingDrag by remember { mutableStateOf<LetterEntry?>(null) }
        LaunchedEffect(pendingDrag) {
            val e = pendingDrag ?: return@LaunchedEffect
            delay(140)
            jump(e)
            // Cleared so the same letter can be reached again by a later drag.
            pendingDrag = null
        }
        // SpaceEvenly puts an equal gap before the first child, between children and after the
        // last, so the letters are NOT a contiguous run of bands starting at y=0. Hit-testing as
        // if they were shifts every tap towards the previous letter.
        val slotPx = with(density) { slot.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val gapPx = ((heightPx - slotPx * visible.size) / (visible.size + 1)).coerceAtLeast(0f)
        val pitchPx = gapPx + slotPx

        fun letterAt(y: Float): LetterEntry? {
            val idx = ((y - gapPx) / pitchPx).toInt().coerceIn(0, visible.lastIndex)
            return visible.getOrNull(idx)?.takeIf { it.offset != null }
        }

        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .focusGroup()
                .onFocusChanged { railFocused = it.hasFocus }
                .background(if (railFocused) colors.surface else colors.background)
                .pointerInput(visible) {
                    detectTapGestures { pos -> letterAt(pos.y)?.let { jump(it) } }
                }
                .pointerInput(visible) {
                    detectDragGestures(
                        onDragEnd = { dragLabel = null },
                        onDragCancel = { dragLabel = null },
                    ) { change, _ ->
                        change.consume()
                        letterAt(change.position.y)?.let { e ->
                            if (dragLabel != e.label) { dragLabel = e.label; pendingDrag = e }
                        }
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            visible.forEach { entry ->
                val enabled = entry.offset != null
                val isCurrent = enabled && entry.label == current
                var focused by remember { mutableStateOf(false) }
                // Same language as a focused card: the letter you are on grows. Colour alone is
                // easy to miss on a small rail at arm's length.
                val pop by animateFloatAsState(
                    targetValue = if (isCurrent) 1.5f else if (focused) 1.25f else 1f,
                    animationSpec = tween(durationMillis = 120),
                    label = "letterPop",
                )
                Box(
                    Modifier
                        .graphicsLayer { scaleX = pop; scaleY = pop }
                        .padding(vertical = 1.dp)
                        .height(slot - 2.dp)
                        .fillMaxWidth(0.85f)
                        .clip(PillShape)
                        .onFocusChanged { focused = it.isFocused }
                        .background(
                            when {
                                isCurrent -> colors.primary
                                focused -> colors.primaryLighten.copy(alpha = 0.35f)
                                else -> androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .alpha(if (enabled) 1f else 0.3f)
                        .then(if (enabled) Modifier.clickable { jump(entry) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        entry.label,
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                        fontWeight = if (isCurrent || focused) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) colors.white else colors.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Fits the rail in the available height: drop empty letters first, then every other one. */
private fun thin(letters: List<LetterEntry>, max: Int): List<LetterEntry> {
    if (letters.size <= max) return letters
    val enabled = letters.filter { it.offset != null }
    if (enabled.size <= max) return enabled
    val step = (enabled.size + max - 1) / max
    return enabled.filterIndexed { i, _ -> i % step == 0 }
}

/** The big floating letter (48 sp) shown for 600 ms after a jump. */
@Composable
fun JumpLabel(label: String?, modifier: Modifier = Modifier) {
    if (label == null) return
    val colors = RommTheme.colors
    Box(
        modifier
            .background(colors.toplayer.copy(alpha = 0.92f), CircleShape)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.displayMedium, color = colors.primaryLighten)
    }
}
