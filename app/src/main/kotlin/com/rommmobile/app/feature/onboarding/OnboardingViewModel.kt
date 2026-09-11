package com.rommmobile.app.feature.onboarding

import com.rommmobile.app.core.input.ButtonMap
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.network.UrlNormalizer
import com.rommmobile.app.core.storage.FileGatewayFactory
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.api.DeviceAuthInitDto
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.mapping.Resolution
import com.rommmobile.app.data.prefs.Launcher
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.AuthRepository
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.Platform
import com.rommmobile.app.data.repo.PlatformRepository
import com.rommmobile.app.data.repo.Session
import com.rommmobile.app.feature.settings.MappingRow
import com.rommmobile.app.feature.settings.MappingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ProbeState {
    data object Idle : ProbeState
    data object Probing : ProbeState
    data class Ok(val url: String, val version: String?) : ProbeState
    data class Error(val message: String) : ProbeState
}

enum class AuthMethod { DEVICE, PASSWORD, TOKEN }

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Busy : AuthUiState
    data class Error(val message: String) : AuthUiState
    data object Done : AuthUiState
}

@Immutable
data class DeviceFlowState(
    val init: DeviceAuthInitDto? = null,
    val secondsLeft: Int = 0,
    val verificationUrl: String = "",
    val error: String? = null,
)

@Immutable
data class OnboardingState(
    val step: Int = 0,
    val serverInput: String = "",
    val probe: ProbeState = ProbeState.Idle,
    val authMethod: AuthMethod = AuthMethod.DEVICE,
    val auth: AuthUiState = AuthUiState.Idle,
    val device: DeviceFlowState = DeviceFlowState(),
    val launcher: Launcher = Launcher.ESDE,
    val root: StorageRoot? = null,
    val rootWritable: Boolean? = null,
    val summary: List<MappingRow> = emptyList(),
    val summaryBusy: Boolean = false,
    val rootDirs: List<String> = emptyList(),
    val cfClientId: String = "",
    val cfClientSecret: String = "",
    val allowInsecureRemote: Boolean = false,
    /** The pad map as it stands, recorded by the Controls step or kept from before. */
    val buttonMap: ButtonMap = ButtonMap.DEFAULT,
    /** Whether the user ever recorded one: a recorded map that equals the default is still theirs. */
    val buttonMapRecorded: Boolean = false,
    /** The Controls step opened its recorder once in this run; leaving and coming back must not again. */
    val controlsPrompted: Boolean = false,
    /** False until the stored settings have been read: before that [buttonMap] is only the default. */
    val loaded: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val serverStore: ServerStore,
    private val settings: SettingsStore,
    private val platforms: PlatformRepository,
    private val mapping: MappingRepository,
    private val localLibrary: LocalLibraryRepository,
    private val log: FileLogger,
) : ViewModel() {

    // Granting "All files access" makes Android kill the app to remount its storage, so the
    // user comes back to a cold start. The step lives in saved state so that cold start reopens
    // the wizard on the folder page they left, not on the server page.
    private val handle = handle
    private val _state = MutableStateFlow(OnboardingState(step = (handle.get<Int>(KEY_STEP) ?: 0).coerceIn(0, LAST_STEP)))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    val session: StateFlow<Session> = auth.session

    private val serverInputFlow = MutableStateFlow("")
    private var devicePollJob: Job? = null

    init {
        // The device-code flow is the default, so start it as soon as the step can be reached.
        viewModelScope.launch {
            val s = settings.snapshot()
            val cfg = serverStore.config.value
            _state.update {
                it.copy(
                    // Prefilled scheme: typing "http://" with a d-pad keyboard is pure pain.
                    serverInput = cfg.baseUrl ?: "http://", launcher = s.launcher, root = s.storageRoot,
                    cfClientId = cfg.cfClientId.orEmpty(), cfClientSecret = cfg.cfClientSecret.orEmpty(),
                    allowInsecureRemote = s.allowInsecureRemote,
                    buttonMap = s.buttonMap,
                    buttonMapRecorded = s.buttonMapRecorded,
                    loaded = true,
                )
            }
            serverInputFlow.value = cfg.baseUrl ?: ""
            if (cfg.baseUrl != null) probeNow(cfg.baseUrl)
            s.storageRoot?.let { checkRoot(it) }
        }
        // Reachability test while typing, debounced 800 ms like romm-mobile's login form.
        viewModelScope.launch {
            serverInputFlow.debounce(800).distinctUntilChanged().collect { input -> if (input.isNotBlank()) probeNow(input) else _state.update { it.copy(probe = ProbeState.Idle) } }
        }
    }

    /* -------- step 1: server -------- */

    fun onServerInput(v: String) {
        _state.update { it.copy(serverInput = v, probe = if (v.isBlank()) ProbeState.Idle else ProbeState.Probing) }
        serverInputFlow.value = v
    }

    private fun probeNow(input: String) = viewModelScope.launch {
        _state.update { it.copy(probe = ProbeState.Probing) }
        auth.probe(input).onSuccess { r ->
            if (_state.value.serverInput == input) _state.update { it.copy(probe = ProbeState.Ok(r.url, r.version)) }
        }.onFailure { e ->
            if (_state.value.serverInput == input) _state.update { it.copy(probe = ProbeState.Error(ApiException.from(e).userMessage(context))) }
        }
    }

    /**
     * Cloudflare Access and the plain-HTTP override have to be reachable from step 1: a tunnel
     * protected by Access answers the heartbeat with its login page, so without these the wizard
     * could never get past the server step for the very setups the app is meant to support.
     */
    fun setAdvanced(cfId: String, cfSecret: String, allowInsecure: Boolean) = viewModelScope.launch {
        serverStore.setCloudflareAccess(cfId.trim().ifBlank { null }, cfSecret.trim().ifBlank { null })
        settings.setAllowInsecureRemote(allowInsecure)
        _state.update { it.copy(cfClientId = cfId.trim(), cfClientSecret = cfSecret.trim(), allowInsecureRemote = allowInsecure) }
        delay(250) // let the interceptor pick the new policy up before probing again
        _state.value.serverInput.takeIf { it.isNotBlank() }?.let { probeNow(it) }
    }

    fun confirmServer(onOk: () -> Unit) = viewModelScope.launch {
        val p = _state.value.probe as? ProbeState.Ok ?: return@launch
        auth.saveServer(p.url, p.version)
        onOk()
    }

    /* -------- step 2: auth -------- */

    fun setAuthMethod(m: AuthMethod) {
        _state.update { it.copy(authMethod = m, auth = AuthUiState.Idle) }
        if (m == AuthMethod.DEVICE) startDeviceFlow() else stopDeviceFlow()
    }

    fun loginPassword(user: String, pass: String) = runAuth { auth.loginWithPassword(user.trim(), pass) }
    fun loginToken(token: String) = runAuth { auth.loginWithClientToken(token) }


    private fun runAuth(block: suspend () -> Result<Unit>) = viewModelScope.launch {
        _state.update { it.copy(auth = AuthUiState.Busy) }
        val r = block()
        _state.update { it.copy(auth = r.fold({ AuthUiState.Done }, { e -> AuthUiState.Error(ApiException.from(e).userMessage(context)) })) }
        if (r.isSuccess) { stopDeviceFlow(); afterLogin() }
    }

    private fun startDeviceFlow() {
        stopDeviceFlow()
        devicePollJob = viewModelScope.launch {
            _state.update { it.copy(device = DeviceFlowState()) }
            val init = auth.deviceFlowStart().getOrElse { e ->
                _state.update { it.copy(device = DeviceFlowState(error = ApiException.from(e).userMessage(context))) }
                return@launch
            }
            val base = serverStore.config.value.activeUrl ?: ""
            val url = UrlNormalizer.join(base, init.verificationPathComplete ?: "/pair/device?user_code=${init.userCode}")
            var left = init.expiresIn
            _state.update { it.copy(device = DeviceFlowState(init, left, url)) }
            val interval = init.interval.coerceIn(2, 15)
            var sinceLastPoll = 0
            while (left > 0) {
                delay(1_000)
                left--; sinceLastPoll++
                _state.update { it.copy(device = it.device.copy(secondsLeft = left)) }
                if (sinceLastPoll >= interval) {
                    sinceLastPoll = 0
                    val r = auth.deviceFlowPoll(init.deviceCode)
                    if (r.getOrNull() == true) {
                        _state.update { it.copy(auth = AuthUiState.Done) }
                        afterLogin()
                        return@launch
                    }
                    r.exceptionOrNull()?.let { e ->
                        _state.update { it.copy(device = it.device.copy(error = ApiException.from(e).userMessage(context))) }
                        return@launch
                    }
                }
            }
            _state.update { it.copy(device = it.device.copy(error = context.getString(com.rommmobile.app.R.string.auth_device_expired))) }
        }
    }

    fun restartDeviceFlow() = startDeviceFlow()

    private fun stopDeviceFlow() { devicePollJob?.cancel(); devicePollJob = null }

    private fun afterLogin() = viewModelScope.launch { platforms.refresh(); log.i("Onboarding", "logged in") }

    /* -------- step 3: launcher -------- */

    fun setLauncher(l: Launcher) {
        _state.update { it.copy(launcher = l) }
        viewModelScope.launch { settings.setLauncher(l); mapping.resetAutomatic() }
    }

    val rootNames: List<String> get() = mapping.map.rootNamesFor(_state.value.launcher)

    /* -------- step 1: controls -------- */

    /** Persisted at once: the rest of the wizard is navigated with the buttons just named. */
    fun markControlsPrompted() = _state.update { it.copy(controlsPrompted = true) }

    fun setButtonMap(map: ButtonMap) {
        _state.update { it.copy(buttonMap = map, buttonMapRecorded = true) }
        viewModelScope.launch { settings.setButtonMap(map); log.i("Input", "button map ${map.encode()}") }
    }

    /* -------- step 5: storage -------- */

    fun setRoot(root: StorageRoot) {
        _state.update { it.copy(root = root, rootWritable = null) }
        viewModelScope.launch { settings.setStorageRoot(root); mapping.resetAutomatic(); checkRoot(root) }
    }

    /** Same root, fresh check: All files access may have just been granted or taken away. */
    fun recheckRoot() {
        val root = _state.value.root ?: return
        _state.update { it.copy(rootWritable = null) }
        viewModelScope.launch { checkRoot(root) }
    }

    private suspend fun checkRoot(root: StorageRoot) {
        val ok = withContext(Dispatchers.IO) { runCatching { FileGatewayFactory.create(context, root).isWritable() }.getOrDefault(false) }
        _state.update { if (it.root == root) it.copy(rootWritable = ok) else it }
    }

    /* -------- step 5: summary -------- */

    fun buildSummary() = viewModelScope.launch {
        _state.update { it.copy(summaryBusy = true) }
        if (platforms.cachedCount() == 0) platforms.refresh()
        val plats: List<Platform> = platforms.platforms.first()
        val gw = _state.value.root?.let { FileGatewayFactory.create(context, it) }
        val results = mapping.discoverAll(plats, gw, _state.value.launcher)
        val rows = results.map { (p, r) ->
            when (r) {
                is Resolution.Resolved -> MappingRow(p, r.mapping, MappingStatus.FOUND, null, emptyList())
                is Resolution.NeedsCreate -> MappingRow(p, null, MappingStatus.TO_CREATE, r.proposedName, emptyList())
                is Resolution.Ambiguous -> MappingRow(p, null, MappingStatus.TO_CHOOSE, r.preselected, r.candidates.map { it.relDir })
                Resolution.NoRoot -> MappingRow(p, null, MappingStatus.UNKNOWN, null, emptyList())
            }
        }
        val dirs = withContext(Dispatchers.IO) { runCatching { gw?.listDirs("")?.sorted() }.getOrNull() ?: emptyList() }
        _state.update { it.copy(summary = rows, summaryBusy = false, rootDirs = dirs) }
    }

    fun summaryChoose(slug: String, relDir: String) = viewModelScope.launch { mapping.setUserMapping(slug, relDir); buildSummary() }
    fun summaryCreate(slug: String, name: String) = viewModelScope.launch { mapping.createFolder(slug, name); buildSummary() }
    fun summaryClear(slug: String) = viewModelScope.launch { mapping.clear(slug); buildSummary() }

    fun goTo(step: Int) {
        val clamped = step.coerceIn(0, LAST_STEP)
        handle[KEY_STEP] = clamped
        _state.update { it.copy(step = clamped) }
    }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        settings.setOnboardingDone(true)
        launch { runCatching { localLibrary.rescanAll() } }
        onDone()
    }

    override fun onCleared() { stopDeviceFlow() }

    companion object {
        // Renamed when the Controls step was put first: a step index saved by an older build
        // would have been reinterpreted rather than ignored.
        private const val KEY_STEP = "onboarding_step_v2"
        /** Controls, server, sign-in, launcher, ROM folder, summary: the screen's STEP_TITLES has one title each. */
        const val LAST_STEP = 5
    }
}
