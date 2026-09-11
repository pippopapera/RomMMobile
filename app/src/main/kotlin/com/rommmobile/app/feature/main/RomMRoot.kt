package com.rommmobile.app.feature.main

import com.rommmobile.app.feature.controls.ButtonMapScreen
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import com.rommmobile.app.R
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.rommmobile.app.core.design.LocalScreenMargin
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.ToastHost
import com.rommmobile.app.core.input.LocalGamepad
import com.rommmobile.app.feature.downloads.DownloadsScreen
import com.rommmobile.app.feature.downloads.engine.DownloadNotifier
import com.rommmobile.app.feature.game.GameDetailScreen
import com.rommmobile.app.feature.library.LibraryScreen
import com.rommmobile.app.feature.onboarding.LoginScreen
import com.rommmobile.app.feature.onboarding.OnboardingScreen
import com.rommmobile.app.feature.settings.DiagnosticsScreen
import com.rommmobile.app.feature.settings.FirmwareScreen
import com.rommmobile.app.feature.settings.FolderMappingsScreen
import com.rommmobile.app.feature.settings.LocalLibraryScreen
import com.rommmobile.app.feature.settings.SettingsScreen

@Composable
fun RomMRoot(intentEpoch: Int = 0) {
    val appVm: AppViewModel = hiltViewModel()
    val start by appVm.startDestination.collectAsStateWithLifecycle()
    val colors = RommTheme.colors

    CompositionLocalProvider(LocalGamepad provides appVm.gamepad) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            when (start) {
                StartDestination.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> AppNavHost(appVm, start, intentEpoch)
            }
            ToastHost(appVm.toaster, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AppNavHost(appVm: AppViewModel, start: StartDestination, intentEpoch: Int) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val margin = LocalScreenMargin.current

    // Every user-driven navigation goes through one gate that stays shut for the length of a
    // transition. Reproduced on the handheld: B pops the library, and a tap that lands ~200 ms
    // later on the still-fading screen hits ITS back arrow (it sits where the rail's first item
    // sits) and pops again mid-transition; NavHost was left with both screens composed and
    // neither drawn, a black app until the process was killed. The fades run 120-150 ms.
    val gate = remember(navController) { NavGate(navController) }
    // B is popped by NavHost itself and never passes through the gate, so the gate watches the
    // back stack instead: any change, however it was caused, shuts it for one transition. Seen
    // on the handheld: B, then a tap ~200 ms later on the still-fading back arrow, two pops in
    // one transition, black app.
    LaunchedEffect(navController) { navController.currentBackStackEntryFlow.collect { gate.touch() } }

    // Session expired anywhere: go back to the login step, keep everything else.
    LaunchedEffect(appVm) {
        appVm.events.collect { e ->
            if (e is AppEvent.SessionExpired) {
                navController.navigate(LoginRoute) { popUpTo(0) { inclusive = true } }
            }
        }
    }
    // Tapping the download notification lands on the queue.
    LaunchedEffect(intentEpoch, start) {
        val intent = (context as? Activity)?.intent
        if (intent?.getBooleanExtra(DownloadNotifier.EXTRA_OPEN_DOWNLOADS, false) == true) {
            intent.removeExtra(DownloadNotifier.EXTRA_OPEN_DOWNLOADS)
            if (start == StartDestination.HOME) gate.go(DownloadsRoute)
        }
    }

    val startRoute: Any = when (start) {
        StartDestination.ONBOARDING -> OnboardingRoute
        StartDestination.LOGIN -> LoginRoute
        else -> HomeRoute
    }

    // B is the only "back" a handheld has, and at the root of the graph navigation-compose's own
    // callback is disabled, so the press fell through to the Activity and dropped the user on the
    // launcher. The root now ARMS an exit rather than taking one: a second press within the window
    // leaves, anything slower is treated as a change of mind. Gated on there being nothing to pop,
    // so every real back - library to platforms, game to library, dialogs, the keyboard dismiss in
    // gamepadTextField - is untouched.
    val backEntry by navController.currentBackStackEntryAsState()
    val atRoot = backEntry != null && navController.previousBackStackEntry == null
    // Long.MIN_VALUE, not 0: armedAt is compared against uptime, and 0 is a real uptime.
    var armedAt by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val exitHint = stringResource(R.string.exit_confirm)
    BackHandler(enabled = atRoot) {
        val now = SystemClock.uptimeMillis()
        // Same length as the toast that says "press again": while the hint is readable, the
        // second press must still count.
        if (armedAt != Long.MIN_VALUE && now - armedAt < 2_600L) {
            (context as? Activity)?.finish()
        } else {
            armedAt = now
            appVm.toaster.show(exitHint)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        // Edge-to-edge: system bars, cutouts and the IME are handled once, here, for every screen.
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(margin),
        enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) },
        popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(onDone = { navController.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } })
        }
        composable<LoginRoute> {
            LoginScreen(onDone = { navController.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } })
        }
        composable<HomeRoute> {
            MainScaffold(
                appVm = appVm,
                onOpenLibrary = { route -> gate.go(route) },
                onOpenGame = { id -> gate.go(GameRoute(id)) },
                onOpenDownloads = { gate.go(DownloadsRoute) },
                onOpenSettings = { gate.go(SettingsRoute) },
            )
        }
        composable<LibraryRoute> { entry ->
            val route = entry.toRoute<LibraryRoute>()
            LibraryScreen(
                source = route.toSource(),
                title = route.title,
                onlyPresent = route.onlyPresent,
                onBack = { gate.pop() },
                onOpenGame = { id -> gate.go(GameRoute(id)) },
                onOpenDownloads = { gate.go(DownloadsRoute) },
            )
        }
        composable<GameRoute> { entry ->
            val route = entry.toRoute<GameRoute>()
            GameDetailScreen(
                romId = route.id,
                onBack = { gate.pop() },
                onOpenGame = { id -> gate.go(GameRoute(id)) },
                onOpenDownloads = { gate.go(DownloadsRoute) },
            )
        }
        composable<DownloadsRoute> { DownloadsScreen(onBack = { gate.pop() }) }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { gate.pop() },
                onOpenMappings = { gate.go(FolderMappingsRoute) },
                onOpenDiagnostics = { gate.go(DiagnosticsRoute) },
                onOpenLocalLibrary = { gate.go(LocalLibraryRoute) },
                onOpenFirmware = { gate.go(FirmwareRoute) },
                onOpenControls = { gate.go(ButtonMapRoute) },
                onLoggedOut = { navController.navigate(LoginRoute) { popUpTo(0) { inclusive = true } } },
                onRerunOnboarding = { navController.navigate(OnboardingRoute) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<FolderMappingsRoute> { FolderMappingsScreen(onBack = { gate.pop() }) }
        composable<DiagnosticsRoute> { DiagnosticsScreen(onBack = { gate.pop() }) }
        composable<LocalLibraryRoute> { LocalLibraryScreen(onBack = { gate.pop() }) }
        composable<FirmwareRoute> { FirmwareScreen(onBack = { gate.pop() }) }
        composable<ButtonMapRoute> { ButtonMapScreen(onBack = { gate.pop() }) }
    }
}

/** See the note in AppNavHost: one navigation per transition, the rest are dropped. */
private class NavGate(private val nav: androidx.navigation.NavHostController) {
    private var last = Long.MIN_VALUE
    fun touch() { last = SystemClock.uptimeMillis() }
    private fun open(): Boolean {
        val now = SystemClock.uptimeMillis()
        // Longer than the 150 ms fade plus a frame or two; short enough that a deliberate second
        // press after the screen has settled still lands.
        if (last != Long.MIN_VALUE && now - last < 350L) return false
        last = now
        return true
    }
    fun go(route: Any) { if (open()) nav.navigate(route) }
    fun pop() { if (open()) nav.popBackStack() }
}
