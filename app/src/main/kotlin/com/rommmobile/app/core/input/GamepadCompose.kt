package com.rommmobile.app.core.input

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlin.math.abs

val LocalGamepad = compositionLocalOf<GamepadBus> { error("GamepadBus not provided") }

/**
 * Collects gamepad actions while the calling composition's lifecycle is RESUMED. Under
 * navigation-compose only the top back stack entry is RESUMED, so a screen behind another
 * never reacts to the same button. [handler] returns true when it consumed the action.
 */
@Composable
fun GamepadActions(vararg keys: Any?, handler: (GamepadAction) -> Boolean) {
    val bus = LocalGamepad.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val current = rememberUpdatedState(handler)
    LaunchedEffect(bus, lifecycleOwner, *keys) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            bus.actions.collect { action ->
                if (!bus.isMuted(action)) current.value(action)
            }
        }
    }
}

/** Marks a modal as open for its whole composition so global shortcuts pause. */
@Composable
fun ModalScope() {
    val bus = LocalGamepad.current
    DisposableEffect(bus) {
        bus.modalDepth += 1
        onDispose { bus.modalDepth -= 1 }
    }
}

/**
 * No shortcut fires while a dialog or sheet owns the screen. Select included: it opens a text
 * field on a library and a row menu on Downloads, and neither belongs under a modal (left live,
 * it slipped the search field under the sort dialog).
 */
@Suppress("UNUSED_PARAMETER")
fun GamepadBus.isMuted(action: GamepadAction): Boolean = modalDepth > 0

/**
 * Continuous scroll driven by the right analog stick. Speed grows with the square of the
 * deflection so a gentle tilt browses and a full tilt flies through long lists.
 */
fun Modifier.rightStickScroll(state: ScrollableState, enabled: Boolean = true): Modifier = composed {
    val bus = LocalGamepad.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    LaunchedEffect(state, enabled, bus) {
        if (!enabled) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            bus.rightStick.filter { abs(it.second) > 0f }.collect {
                while (isActive && abs(bus.rightStick.value.second) > 0f) {
                    val v = bus.rightStick.value.second
                    val pxPerFrame = with(density) { 40.dp.toPx() } * v * abs(v)
                    state.scrollBy(pxPerFrame)
                    awaitFrame()
                }
            }
        }
    }
    this
}
