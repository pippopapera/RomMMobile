package com.rommmobile.app.core.input

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * D-pad navigation for a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * Compose's own 2D focus search drifts sideways in a lazy grid: when the row below is not
 * composed yet it grows the viewport one item at a time and takes the FIRST item focus search
 * accepts, which is the leftmost one. Holding the d-pad makes it worse, because each repeat
 * runs the search against geometry that the previous scroll animation is still moving.
 *
 * So vertical movement is computed here instead, from the item index, and the column the user
 * last chose with left/right is remembered ([goalColumn]) so a short last row does not steal it.
 * Horizontal movement is left to the default search, which handles it correctly and still lets
 * focus leave the grid at the edges.
 */
@Stable
class GridFocusCoordinator(
    private val state: LazyGridState,
    private val scope: CoroutineScope,
) {
    private val requesters = HashMap<Int, FocusRequester>()

    var columns by mutableIntStateOf(1)
    var itemCount by mutableIntStateOf(0)

    /** Index the user is on, tracked as focus lands. */
    var cursor by mutableIntStateOf(0)
        private set

    /** False until something actually put the cursor somewhere; before that it is just 0. */
    var cursorValid by mutableStateOf(false)
        private set

    /** Column chosen with left/right; vertical moves try to come back to it. */
    private var goalColumn = 0
    private var pending: Job? = null

    fun register(index: Int, requester: FocusRequester) { requesters[index] = requester }
    fun unregister(index: Int) { requesters.remove(index) }

    fun onItemFocused(index: Int) {
        cursor = index
        cursorValid = true
        if (columns > 0) goalColumn = index % columns
    }

    /** Moves the cursor without asking for focus; for taps, where no focus ring is wanted. */
    fun moveCursor(index: Int) {
        if (itemCount <= 0) return
        cursor = index.coerceIn(0, itemCount - 1)
        cursorValid = true
        if (columns > 0) goalColumn = cursor % columns
    }

    /** Target for a vertical move, or null when focus should leave the grid. */
    private fun verticalTarget(down: Boolean): Int? {
        val cols = columns.coerceAtLeast(1)
        val row = cursor / cols
        val lastRow = ((itemCount - 1).coerceAtLeast(0)) / cols
        return if (down) {
            if (row >= lastRow) null
            else ((row + 1) * cols + goalColumn).coerceAtMost(itemCount - 1)
        } else {
            if (row == 0) null else (row - 1) * cols + goalColumn
        }
    }

    /** @return true when the move was handled here. */
    fun move(down: Boolean): Boolean {
        val target = verticalTarget(down) ?: return false
        cursor = target
        pending?.cancel()
        pending = scope.launch { focusIndex(target) }
        return true
    }

    /** Puts focus on the first cell; used when a screen opens with a pad connected. */
    fun focusFirst() = focusAt(0)

    /** Moves the cursor to [index], scrolling the grid there first when it is not composed. */
    fun focusAt(index: Int) {
        if (itemCount <= 0) return
        val target = index.coerceIn(0, itemCount - 1)
        cursor = target
        cursorValid = true
        if (columns > 0) goalColumn = target % columns
        pending?.cancel()
        pending = scope.launch { focusIndex(target) }
    }

    private suspend fun focusIndex(target: Int) {
        val direction = FocusDirection.Enter
        requesters[target]?.let { if (it.requestFocus(direction)) return }
        // Off-screen: ask for the row, then focus once it has been placed. requestScrollToItem
        // is not a suspend call and a later request simply replaces this one, which is exactly
        // what a held direction needs.
        state.requestScrollToItem(target)
        repeat(5) {
            withFrameNanos { }
            requesters[target]?.let { if (it.requestFocus(direction)) return }
        }
    }
}

@Composable
fun rememberGridFocusCoordinator(state: LazyGridState): GridFocusCoordinator {
    val scope = rememberCoroutineScope()
    return remember(state, scope) { GridFocusCoordinator(state, scope) }
}

/**
 * Handles only up/down. Everything else, including left/right, click and long click, is passed
 * through so default behaviour and grid exit keep working.
 */
fun Modifier.gridDpadNavigation(coordinator: GridFocusCoordinator): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> coordinator.move(down = true)
            Key.DirectionUp -> coordinator.move(down = false)
            else -> false
        }
    }

/**
 * Registers one grid cell with the coordinator and keeps the focused cell composed while the
 * grid scrolls under it, so its FocusRequester never detaches mid-move.
 */
@Composable
fun Modifier.gridFocusItem(index: Int, coordinator: GridFocusCoordinator): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(index, coordinator) {
        coordinator.register(index, requester)
        onDispose { coordinator.unregister(index) }
    }
    val pinnable = LocalPinnableContainer.current
    var pin by remember { mutableStateOf<PinnableContainer.PinnedHandle?>(null) }
    DisposableEffect(Unit) { onDispose { pin?.release(); pin = null } }
    return this
        .focusRequester(requester)
        .onFocusChanged { st ->
            if (st.isFocused) {
                coordinator.onItemFocused(index)
                if (pin == null) pin = pinnable?.pin()
            } else {
                pin?.release()
                pin = null
            }
        }
}

/**
 * Keeps the focused row at a fixed height in the viewport. Without a pivot the grid re-centres
 * on every step and a held direction turns into a moving target.
 */
val GridBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = 0.35f * containerSize
        val target = if (size <= containerSize && (containerSize - pivot) < size) containerSize - size else pivot
        return offset - target
    }

    override val scrollAnimationSpec: AnimationSpec<Float> = tween(120, easing = LinearEasing)
}
