package com.rommmobile.app

import com.rommmobile.app.core.input.remapKey
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
     * Every key goes through the user's button map first (see [remapKey] for what each printed
     * button becomes). While a mapper is recording, it gets the raw key and nothing else does;
     * the system's own keys stay with the system, or the volume could not be set and the
     * recorder could not be left.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!GamepadBus.isSystemKey(event.keyCode) && gamepad.offerToCapture(event)) return true
        return gamepad.remapKey(event, dispatch = { super.dispatchKeyEvent(it) }, onBack = { onBackPressedDispatcher.onBackPressed() })
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepad.onMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }
}
