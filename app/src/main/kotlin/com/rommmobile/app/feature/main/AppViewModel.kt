package com.rommmobile.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.input.GamepadBus
import com.rommmobile.app.core.network.ApiErrorKind
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.CleartextPolicyInterceptor
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.network.SessionEvent
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.AuthRepository
import com.rommmobile.app.data.repo.CollectionRepository
import com.rommmobile.app.data.repo.DownloadRepository
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.PlatformRepository
import com.rommmobile.app.data.repo.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StartDestination { LOADING, ONBOARDING, LOGIN, HOME }

sealed interface AppEvent {
    data object SessionExpired : AppEvent
}

@HiltViewModel
class AppViewModel @Inject constructor(
    val gamepad: GamepadBus,
    val toaster: Toaster,
    private val settings: SettingsStore,
    private val auth: AuthRepository,
    private val serverStore: ServerStore,
    private val platforms: PlatformRepository,
    private val collections: CollectionRepository,
    private val localLibrary: LocalLibraryRepository,
    private val downloads: DownloadRepository,
    private val cleartextPolicy: CleartextPolicyInterceptor,
    private val log: FileLogger,
) : ViewModel() {

    val startDestination: StateFlow<StartDestination> = combine(settings.onboardingDone, auth.session) { done, session ->
        when {
            !done -> StartDestination.ONBOARDING
            session is Session.LoggedIn -> StartDestination.HOME
            else -> StartDestination.LOGIN
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, StartDestination.LOADING)

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    val openDownloadCount: StateFlow<Int> = downloads.observeOpen().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val serverVersion: StateFlow<String?> = serverStore.config.map { it.serverVersion }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True while requests leave through the remote address (the tunnel) rather than the LAN one. */
    val remoteActive: StateFlow<Boolean> = serverStore.config.map { it.isOnRemote }
        .stateIn(viewModelScope, SharingStarted.Eagerly, serverStore.config.value.isOnRemote)
    private val com.rommmobile.app.core.network.ServerConfig.isOnRemote: Boolean
        get() = remoteUrl != null && activeUrl != null && activeUrl == remoteUrl

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                cleartextPolicy.allowInsecureRemote = s.allowInsecureRemote
            }
        }
        viewModelScope.launch {
            auth.sessionEvents.collect { e -> if (e is SessionEvent.Expired) _events.tryEmit(AppEvent.SessionExpired) }
        }
        viewModelScope.launch {
            auth.session.collect { s -> if (s is Session.LoggedIn) warmUp(scanLocal = true) }
        }
    }

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var warmUpJob: Job? = null
    private var lastGoodWarmUp = 0L

    private fun isConnectivity(t: Throwable?): Boolean {
        if (t == null) return false
        val kind = ApiException.from(t).kind
        return kind == ApiErrorKind.OFFLINE || kind == ApiErrorKind.TIMEOUT
    }

    /**
     * Re-probes which endpoint to use and refreshes. This is the ONLY thing that re-runs endpoint
     * selection, so anything offering the user a way back online has to come through here: a plain
     * platform refresh would just retry the address we already decided was the right one.
     */
    fun retryOnline() { warmUp(scanLocal = false) }

    /**
     * Runs the start-up refreshes; only the local scan has to wait for something.
     *
     * [scanLocal] is true once per session (login) and for the manual sync. It used to run on
     * every return to the Home screen as well: twenty folders listed through SAF, twenty table
     * rewrites, and every list on screen rebuilt after each one, all to notice a file copied over
     * from a PC - which is what the sync button is for.
     */
    private fun warmUp(scanLocal: Boolean) {
        if (warmUpJob?.isActive == true) return
        warmUpJob = viewModelScope.launch {
            val endpoint = async { runCatching { auth.selectEndpoint() } }
            val racing = async { platforms.refresh() }
            endpoint.await()
            // The first refresh left before the endpoint was chosen, so it went to whatever
            // activeUrl held at start-up: always the LAN address, reachable or not. A connectivity
            // failure there says nothing about the endpoint we ended up on, so ask once more now
            // that it is decided instead of latching the banner on a race.
            var result = racing.await()
            if (isConnectivity(result.exceptionOrNull())) result = platforms.refresh()
            val list = result.onSuccess {
                _offline.value = false
                lastGoodWarmUp = System.currentTimeMillis()
            }.onFailure { t ->
                _offline.value = isConnectivity(t)
                log.w("App", "platform refresh failed: ${ApiException.from(t).message}")
            }.getOrNull()
            // The scan matches folders against the platform list, so it cannot run beside the
            // refresh: on a fresh install it would run against an empty list and index nothing,
            // which is why "Installed" came up empty on the first launch.
            if (scanLocal) syncLocal(list)
            launch { collections.refreshUser() }
            launch { collections.refreshSmart() }
            launch { collections.refreshVirtual() }
        }
    }

    private suspend fun syncLocal(list: List<com.rommmobile.app.data.repo.Platform>?) {
        val plats = list ?: runCatching { platforms.platforms.first() }.getOrNull() ?: return
        _syncing.value = true
        runCatching { localLibrary.sync(plats) }.onFailure { log.w("App", "local sync failed: ${it.message}") }
        _syncing.value = false
    }

    /** Manual "sync now", from the Installed screen. */
    fun syncLocalLibrary() {
        if (_syncing.value) return
        viewModelScope.launch { syncLocal(null) }
    }

    /**
     * Called when the app comes back to the foreground. A handheld gets carried between the home
     * network and everywhere else while the app is only backgrounded, and endpoint selection would
     * otherwise stay frozen on whatever was right when the process started.
     */
    fun onForeground() {
        // On the tunnel, every resume re-asks the LAN: a session stuck on the remote address while
        // sitting at home is the slowest state this app has, and nothing else ever leaves it.
        if (_offline.value || remoteActive.value || System.currentTimeMillis() - lastGoodWarmUp > 30_000) warmUp(scanLocal = false)
    }
}
