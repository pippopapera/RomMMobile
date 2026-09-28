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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommTheme

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
    /** The finger left the rail (or tapped): whatever the jumps deferred can happen now. */
    onRelease: () -> Unit = {},
    width: Dp = RommTheme.layout.alphabetRailWidth,
) {
    if (letters.isEmpty()) return
    val colors = RommTheme.colors
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val railViewConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            // The rail handles gaps itself. Expanded child targets would steal taps on a
            // disabled letter and select an enabled neighbour instead.
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    val jump by rememberUpdatedState(onJump)
    val release by rememberUpdatedState(onRelease)

    BoxWithConstraints(modifier.width(width).fillMaxHeight()) {
        // Keep every letter. Compact the spacing on short screens, but never shrink the
        // text to make it fit: with very little height or large fonts the rail scrolls.
        val minimumSlot = with(density) { 10.sp.toDp() } + 2.dp
        val preferredSlot = maxOf(14.dp, minimumSlot)
        // Use whole pixels so rounding each child cannot turn a fitting rail into an overflow.
        val slotPx = (constraints.maxHeight / letters.size).coerceIn(
            with(density) { minimumSlot.roundToPx() },
            with(density) { preferredSlot.roundToPx() },
        ).toFloat()
        val slot = with(density) { slotPx.toDp() }
        val scroll = rememberScrollState()
        var railFocused by remember { mutableStateOf(false) }
        var dragLabel by remember { mutableStateOf<String?>(null) }
        // SpaceEvenly puts an equal gap before the first child, between children and after the
        // last, so the letters are NOT a contiguous run of bands starting at y=0. Hit-testing as
        // if they were shifts every tap towards the previous letter.
        val heightPx = constraints.maxHeight.toFloat()
        val gapPx = ((heightPx - slotPx * letters.size) / (letters.size + 1)).coerceAtLeast(0f)
        val pitchPx = gapPx + slotPx
        val scrollable = slotPx * letters.size > heightPx
        val paddingPx = with(density) { 2.dp.toPx() }
        val maxPop = ((pitchPx - paddingPx) / (slotPx - paddingPx)).coerceAtLeast(1f)

        // L1/R1 can reach letters outside a very short viewport. Keep that selection visible.
        LaunchedEffect(current, letters, slotPx, heightPx) {
            val index = letters.indexOfFirst { it.label == current }
            if (index >= 0 && scrollable) {
                val top = gapPx + index * pitchPx
                val bottom = top + slotPx
                val target = when {
                    top < scroll.value -> top.toInt()
                    bottom > scroll.value + heightPx -> (bottom - heightPx).toInt()
                    else -> scroll.value
                }
                scroll.animateScrollTo(target.coerceAtLeast(0))
            }
        }

        fun letterAt(y: Float): LetterEntry? {
            val idx = ((y + scroll.value - gapPx) / pitchPx).toInt().coerceIn(0, letters.lastIndex)
            return letters[idx].takeIf { it.offset != null }
        }

        CompositionLocalProvider(LocalViewConfiguration provides railViewConfiguration) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .focusGroup()
                .onFocusChanged { railFocused = it.hasFocus }
                .background(if (railFocused) colors.surface else colors.background)
                .pointerInput(letters, slotPx, heightPx) {
                    detectTapGestures { pos -> letterAt(pos.y)?.let { jump(it); release() } }
                }
                .pointerInput(letters, slotPx, heightPx, scrollable) {
                    // In the overflow fallback a swipe reveals more letters; tapping still jumps.
                    if (scrollable) return@pointerInput
                    detectDragGestures(
                        onDragEnd = { dragLabel = null; release() },
                        onDragCancel = { dragLabel = null; release() },
                    ) { change, _ ->
                        change.consume()
                        // Every letter crossed jumps at once: the list has to move under the
                        // finger, not once it rests. Moving is free; what the screen defers until
                        // the finger rests or lifts is telling Paging to fetch there.
                        letterAt(change.position.y)?.let { e ->
                            if (dragLabel != e.label) { dragLabel = e.label; jump(e) }
                        }
                    }
                }
                .verticalScroll(scroll, enabled = scrollable)
                .heightIn(min = maxHeight),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { entry ->
                val enabled = entry.offset != null
                val isCurrent = enabled && entry.label == current
                var focused by remember { mutableStateOf(false) }
                // Same language as a focused card: the letter you are on grows. Colour alone is
                // easy to miss on a small rail at arm's length.
                val pop by animateFloatAsState(
                    // Dense rails keep the colour/bold highlight without covering neighbours.
                    targetValue = minOf(if (isCurrent) 1.5f else if (focused) 1.25f else 1f, maxPop),
                    animationSpec = tween(durationMillis = 120),
                    label = "letterPop",
                )
                Box(
                    Modifier
                        .graphicsLayer { scaleX = pop; scaleY = pop }
                        .height(slot)
                        .padding(vertical = 1.dp)
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
                        .then(if (enabled) Modifier.clickable { jump(entry); release() } else Modifier),
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
