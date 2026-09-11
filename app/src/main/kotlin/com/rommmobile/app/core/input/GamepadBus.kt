package com.rommmobile.app.core.input

import android.os.SystemClock
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
import kotlin.math.max

/** High level actions produced by physical controls, independent from the button that fired them. */
enum class GamepadAction {
    DOWNLOAD,        // X
    TOGGLE_VIEW,     // Y
    PREV_SECTION,    // L1
    NEXT_SECTION,    // R1
    OPEN_SEARCH,     // L3
    OPEN_DOWNLOADS,  // R3
    CONTEXT_MENU,    // Select, the printed one
    FILTERS,         // Start, the printed one
}

/**
 * Central dispatcher for physical controls. Lives as a singleton so the Activity can feed it and
 * any composable can observe it. Every key goes through the [ButtonMap] first, so the rest of the
 * app reasons in printed buttons; only non-navigation buttons become actions here, the d-pad, A
 * and B keep their framework semantics (focus move, click, back) via [remapKey].
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

    /** Physical keycode per printed button; observable so hint bars follow a remap at once. */
    private val _map = MutableStateFlow(ButtonMap.DEFAULT)
    val mapFlow: StateFlow<ButtonMap> = _map.asStateFlow()
    var map: ButtonMap
        get() = _map.value
        set(value) { _map.value = value }

    /**
     * While a mapper is recording, it owns every key: the activity hands raw events here and
     * consumes them, so no shortcut, click or back can fire from a button the user is naming.
     */
    @Volatile private var capture: ((KeyEvent) -> Unit)? = null
    val capturing: Boolean get() = capture != null

    /** Keycodes physically held right now, so a hint can show the pressed face of its button. */
    private val _held = MutableStateFlow<Set<Int>>(emptySet())
    val held: StateFlow<Set<Int>> = _held.asStateFlow()

    private val heldKeys = HashSet<Int>()

    /** The printed button each physical key resolved to when it went down, until it comes up. */
    private val pressed = HashMap<Int, LogicalButton?>()

    /** Physical key to printed button; a menu key nobody mapped still means Select. */
    fun logicalFor(code: Int): LogicalButton? = map.logicalFor(code) ?: if (code == KeyEvent.KEYCODE_MENU) LogicalButton.SELECT else null

    /**
     * Same, but stable across one press: the map may be replaced between a key's DOWN and its UP
     * (the mapping screen itself is driven by the pad), and the UP must finish what the DOWN began.
     */
    fun logicalForPress(event: KeyEvent): LogicalButton? = when (event.action) {
        KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 || event.keyCode !in pressed) logicalFor(event.keyCode).also { pressed[event.keyCode] = it } else pressed[event.keyCode]
        KeyEvent.ACTION_UP -> pressed.remove(event.keyCode) ?: logicalFor(event.keyCode)
        else -> logicalFor(event.keyCode)
    }

    /**
     * Routes raw keys to [sink] until [endCapture]. The press that opened the recorder is still
     * down at this point and its release will never reach the normal path, so the held set is
     * cleared on both ends or a sprite would stay lit for the rest of the process.
     */
    fun beginCapture(sink: (KeyEvent) -> Unit) {
        capture = sink
        clearHeld()
    }

    fun endCapture(sink: (KeyEvent) -> Unit) {
        if (capture === sink) capture = null
        clearHeld()
    }

    /** True when the event was taken by a recorder and must go nowhere else. */
    fun offerToCapture(event: KeyEvent): Boolean {
        val sink = capture ?: return false
        sink(event)
        return true
    }

    private fun clearHeld() {
        heldKeys.clear()
        pressed.clear()
        _held.value = emptySet()
        leftTrigger = false
        rightTrigger = false
        _rightStick.value = 0f to 0f
    }

    /**
     * @return true/false when the event was fully decided here, null to let the window have it.
     */
    fun onKeyEvent(event: KeyEvent): Boolean? {
        val code = event.keyCode
        val logical = logicalFor(code)
        val raw = isRawGamepadKey(code)
        if (logical == null && !raw) return null
        // Tracked for every pad button, A and B included, before the early returns below. Physical
        // codes: the sprites look them up through the map when they light. Not the directions:
        // no sprite lights for them and a new set per d-pad press would recompose every hint.
        if (logical?.isDirection != true) when (event.action) {
            KeyEvent.ACTION_DOWN -> if (code !in _held.value) _held.value = _held.value + code
            KeyEvent.ACTION_UP -> if (code in _held.value) _held.value = _held.value - code
        }

        val action = actionFor(logical, code)
        if (action == null) {
            return when {
                // The framework and remapKey own these.
                logical != null && (logical.isDirection || logical == LogicalButton.A || logical == LogicalButton.B) -> null
                // A pad key the map does not name (or a trigger with no job): swallowed, so the
                // platform never applies its generic fallbacks (BUTTON_Y becomes BACK, ...).
                else -> true
            }
        }
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

        capture?.let { sink ->
            // Nothing scrolls under the recorder. The triggers are analogue on some handhelds and
            // never produce a key: crossing half travel is turned into a press of their canonical
            // code, which is what the recorder would have been handed on a pad with switches.
            // The event is still left unhandled: a d-pad reported as a hat only becomes DPAD_*
            // keys through the platform's synthesis, which runs for unhandled motion alone.
            _rightStick.value = 0f to 0f
            val l = max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
            val r = max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
            if (l != leftTrigger) { leftTrigger = l; sink(triggerKey(event, KeyEvent.KEYCODE_BUTTON_L2, l)) }
            if (r != rightTrigger) { rightTrigger = r; sink(triggerKey(event, KeyEvent.KEYCODE_BUTTON_R2, r)) }
            return false
        }

        val rx = deadZone(event.getAxisValue(MotionEvent.AXIS_Z))
        val ry = deadZone(event.getAxisValue(MotionEvent.AXIS_RZ))
        val prev = _rightStick.value
        if (prev.first != rx || prev.second != ry) _rightStick.value = rx to ry

        // Left stick is converted to D-pad by the framework itself; never consume the event.
        return false
    }

    private var leftTrigger = false
    private var rightTrigger = false

    private fun triggerKey(from: MotionEvent, code: Int, down: Boolean): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0, from.deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD)
    }

    private fun deadZone(v: Float): Float = if (abs(v) < 0.25f) 0f else v

    /**
     * Printed button to action. The sticks are not in the map (no handheld gets those wrong) and
     * keep their raw codes; the d-pad, A and B have no action because the framework owns them.
     */
    private fun actionFor(logical: LogicalButton?, code: Int): GamepadAction? = when (logical) {
        LogicalButton.X -> GamepadAction.DOWNLOAD
        LogicalButton.Y -> GamepadAction.TOGGLE_VIEW
        LogicalButton.L1 -> GamepadAction.PREV_SECTION
        LogicalButton.R1 -> GamepadAction.NEXT_SECTION
        LogicalButton.START -> GamepadAction.FILTERS
        LogicalButton.SELECT -> GamepadAction.CONTEXT_MENU
        LogicalButton.UP, LogicalButton.DOWN, LogicalButton.LEFT, LogicalButton.RIGHT,
        LogicalButton.A, LogicalButton.B, LogicalButton.L2, LogicalButton.R2 -> null
        null -> when (code) {
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadAction.OPEN_SEARCH
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadAction.OPEN_DOWNLOADS
            else -> null
        }
    }

    /** Pad keys the map does not name, still owned here so no platform fallback fires for them. */
    private fun isRawGamepadKey(code: Int): Boolean =
        KeyEvent.isGamepadButton(code) || code == KeyEvent.KEYCODE_BUTTON_THUMBL || code == KeyEvent.KEYCODE_BUTTON_THUMBR

    companion object {
        /** Keys the recorder must never take: the system needs them, and the user needs the volume. */
        fun isSystemKey(code: Int): Boolean = when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_CAMERA -> true
            else -> false
        }
    }
}
