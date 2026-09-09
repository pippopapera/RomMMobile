package com.rommmobile.app.core.input

import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.Context

private fun hasGamepadNow(): Boolean = InputDevice.getDeviceIds().any { id ->
    val d = InputDevice.getDevice(id) ?: return@any false
    val s = d.sources
    (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
        (s and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
}

/** True while at least one gamepad / joystick / D-pad device is attached (handhelds always). */
@Composable
fun rememberHasGamepad(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(hasGamepadNow()) }
    DisposableEffect(context) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { state.value = hasGamepadNow() }
            override fun onInputDeviceRemoved(deviceId: Int) { state.value = hasGamepadNow() }
            override fun onInputDeviceChanged(deviceId: Int) { state.value = hasGamepadNow() }
        }
        im.registerInputDeviceListener(listener, null)
        onDispose { im.unregisterInputDeviceListener(listener) }
    }
    return state
}
