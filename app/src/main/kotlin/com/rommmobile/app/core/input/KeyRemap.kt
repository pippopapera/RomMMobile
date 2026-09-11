package com.rommmobile.app.core.input

import android.view.KeyEvent

/**
 * The one place a key is turned into what the framework expects for its printed button. Shared by
 * the Activity window and by every dialog window, which has its own key dispatch and would
 * otherwise keep the raw layout (its platform fallback turns BUTTON_B into back and BUTTON_A into
 * a click, whatever the user's map says).
 *
 * - Buttons with an app action are consumed by the bus (no platform fallback may fire for them).
 * - A becomes DPAD_CENTER, the click Compose understands: the platform's own BUTTON_A fallback
 *   only fires when the whole window leaves the key unhandled, which is not dependable once the
 *   app listens for gamepad keys at all.
 * - B on its canonical key is left to the platform, whose BUTTON_B fallback is the back that
 *   predictive back listens to. B on any other key cannot borrow that fallback (it is computed
 *   from the ORIGINAL keycode, after the app reports it unhandled), so [onBack] is called instead.
 * - A direction on an odd keycode is re-dispatched as its DPAD_* code.
 * - Whatever was re-dispatched is reported handled, or the platform would add its own fallback
 *   for the original keycode on top.
 *
 * @param dispatch the window's own dispatch for an event the app does not rewrite (or rewrote).
 */
fun GamepadBus.remapKey(event: KeyEvent, dispatch: (KeyEvent) -> Boolean, onBack: () -> Unit): Boolean {
    // Platform fallbacks (a stick reported as DPAD, BUTTON_A turned into DPAD_CENTER) are already
    // translations of a key that went through here once: never map them again.
    if (event.flags and KeyEvent.FLAG_FALLBACK != 0) return dispatch(event)
    onKeyEvent(event)?.let { return it }
    return when (val logical = logicalForPress(event)) {
        null -> dispatch(event)
        LogicalButton.A -> { dispatch(event.withKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)); true }
        LogicalButton.B -> when {
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B -> dispatch(event)
            else -> { if (event.action == KeyEvent.ACTION_UP) onBack(); true }
        }
        else -> when (event.keyCode) {
            logical.canonical -> dispatch(event)
            else -> { dispatch(event.withKeyCode(logical.canonical)); true }
        }
    }
}

private fun KeyEvent.withKeyCode(code: Int): KeyEvent = KeyEvent(
    downTime, eventTime, action, code, repeatCount, metaState, deviceId, scanCode, flags, source,
)
