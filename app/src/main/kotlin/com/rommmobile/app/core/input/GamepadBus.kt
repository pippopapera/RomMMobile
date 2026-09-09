package com.rommmobile.app.core.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** High level actions produced by physical controls, independent from the button that fired them. */
enum class GamepadAction {
    DOWNLOAD,        // X
    TOGGLE_VIEW,     // Y
    PREV_SECTION,    // L1
    NEXT_SECTION,    // R1
    OPEN_SEARCH,     // L3
    OPEN_DOWNLOADS,  // R3
    CONTEXT_MENU,    // Start
    FILTERS,         // Select
}

/**
 * Central dispatcher for physical controls. Lives as a singleton so the Activity can feed
 * it and any composable can observe it. Only non-navigation buttons are routed here:
 * D-pad, A and B keep their framework semantics (focus move, click, back).
 */
@Singleton
class GamepadBus @Inject constructor() {

    private val _actions = MutableSharedFlow<GamepadAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<GamepadAction> = _actions.asSharedFlow()

    /** Right stick position, in [-1, 1]. Consumers scroll while it is outside the dead zone. */
    private val _rightStick = MutableStateFlow(0f to 0f)
    val rightStick: StateFlow<Pair<Float, Float>> = _rightStick.asStateFlow()

    /** True while a modal (dialog / sheet) owns the screen: section shortcuts are muted. */
    @Volatile var modalDepth: Int = 0

    /** Keycodes physically held right now, so a hint can show the pressed face of its button. */
    private val _held = MutableStateFlow<Set<Int>>(emptySet())
    val held: StateFlow<Set<Int>> = _held.asStateFlow()

    private val heldKeys = HashSet<Int>()

    /**
     * @return true/false when the event was fully decided here, null to let the Activity dispatch it.
     */
    fun onKeyEvent(event: KeyEvent): Boolean? {
        val code = event.keyCode
        if (!isGamepadKey(code)) return null
        // Tracked for every pad key, A and B included, before the early return below.
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (code !in _held.value) _held.value = _held.value + code
            KeyEvent.ACTION_UP -> if (code in _held.value) _held.value = _held.value - code
        }

        val action = mapKey(code) ?: return null // A / B: framework fallbacks do the right thing
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && heldKeys.add(code)) _actions.tryEmit(action)
            }
            KeyEvent.ACTION_UP -> heldKeys.remove(code)
        }
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        val isJoystick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isJoystick || event.action != MotionEvent.ACTION_MOVE) return false

        val rx = deadZone(event.getAxisValue(MotionEvent.AXIS_Z))
        val ry = deadZone(event.getAxisValue(MotionEvent.AXIS_RZ))
        val prev = _rightStick.value
        if (prev.first != rx || prev.second != ry) _rightStick.value = rx to ry

        // Left stick is converted to D-pad by the framework itself; never consume the event.
        return false
    }

    private fun deadZone(v: Float): Float = if (abs(v) < 0.25f) 0f else v

    private fun mapKey(code: Int): GamepadAction? = when (code) {
        KeyEvent.KEYCODE_BUTTON_X -> GamepadAction.DOWNLOAD
        KeyEvent.KEYCODE_BUTTON_Y -> GamepadAction.TOGGLE_VIEW
        KeyEvent.KEYCODE_BUTTON_L1 -> GamepadAction.PREV_SECTION
        KeyEvent.KEYCODE_BUTTON_R1 -> GamepadAction.NEXT_SECTION
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadAction.OPEN_SEARCH
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadAction.OPEN_DOWNLOADS
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> GamepadAction.CONTEXT_MENU
        KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadAction.FILTERS
        else -> null
    }

    private fun isGamepadKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU -> true
        else -> false
    }
}
