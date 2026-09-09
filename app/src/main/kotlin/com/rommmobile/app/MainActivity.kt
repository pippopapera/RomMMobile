package com.rommmobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.ThemeMode
import com.rommmobile.app.core.input.GamepadBus
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.feature.main.RomMRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Bumped on every new intent so the navigation graph re-reads its extras. */
    private val pendingIntentEpoch = androidx.compose.runtime.mutableIntStateOf(0)

    @Inject lateinit var gamepad: GamepadBus
    @Inject lateinit var settings: SettingsStore

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Without this the download progress notification is silently dropped on Android 13+,
        // and a queue running in the background becomes invisible.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val screenMargin by settings.screenMarginDp.collectAsStateWithLifecycle(initialValue = 0)
            RommTheme(themeMode = themeMode, screenMarginDp = screenMargin) {
                RomMRoot(intentEpoch = pendingIntentEpoch.intValue)
            }
        }
    }

    /** singleTask: a notification tap on a live app arrives here, not in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntentEpoch.intValue += 1
    }

    /**
     * Gamepad buttons other than A/B/D-pad are consumed by [GamepadBus] so the framework never
     * applies its generic fallbacks (BUTTON_X becomes DPAD_CENTER, BUTTON_Y becomes BACK, ...).
     * A, B and the D-pad fall through: the framework turns them into DPAD_CENTER/BACK which Compose
     * already understands for click and back navigation. When the user enabled the A/B swap we
     * re-dispatch the opposite button so the fallback chain produces the intended result.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val decided = gamepad.onKeyEvent(event)
        if (decided != null) return decided
        // The AOSP fallback that turns BUTTON_A into DPAD_CENTER only fires when the whole window
        // leaves the key unhandled, which is not dependable once the app listens for gamepad keys
        // at all: the confirm button lit up under the cursor and A did nothing to it. Translating
        // here makes "A activates" and "B goes back" facts of this app instead of a hope about the
        // device's key character map.
        // Only A. B is deliberately left alone: back is delivered through the predictive-back
        // dispatcher, not through key dispatch, so a synthesised KEYCODE_BACK here goes nowhere -
        // whereas the platform fallback from BUTTON_B already reaches it correctly.
        val translated = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> 0
        }
        if (translated != 0) {
            return super.dispatchKeyEvent(
                KeyEvent(
                    event.downTime, event.eventTime, event.action, translated,
                    event.repeatCount, event.metaState, event.deviceId, event.scanCode,
                    event.flags, event.source,
                )
            )
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepad.onMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }
}
