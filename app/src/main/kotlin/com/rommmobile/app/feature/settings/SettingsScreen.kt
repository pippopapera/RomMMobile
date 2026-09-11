package com.rommmobile.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.BuildConfig
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.SwitchRow
import com.rommmobile.app.core.design.ThemeMode
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadTextField
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.ServerConfig
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.prefs.Launcher
import com.rommmobile.app.data.prefs.Settings
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.AuthRepository
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.Session
import com.rommmobile.app.feature.onboarding.RootChooserDialog
import com.rommmobile.app.feature.onboarding.launcherDescRes
import com.rommmobile.app.feature.onboarding.launcherLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val serverStore: ServerStore,
    private val auth: AuthRepository,
    private val mapping: MappingRepository,
    private val localLibrary: LocalLibraryRepository,
    private val platformRepo: com.rommmobile.app.data.repo.PlatformRepository,
    private val toaster: Toaster,
) : ViewModel() {
    val settings: StateFlow<Settings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val platforms: StateFlow<List<com.rommmobile.app.data.repo.Platform>> =
        platformRepo.platforms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFolderPerGame(slug: String, enabled: Boolean) = viewModelScope.launch {
        val current = store.snapshot().folderPerGameSlugs
        store.setFolderPerGame(if (enabled) current + slug else current - slug)
    }

    fun setExtractOverride(slug: String, extract: Boolean?) = viewModelScope.launch { store.setExtractOverride(slug, extract) }
    val server: StateFlow<ServerConfig> = serverStore.config
    val session: StateFlow<Session> = auth.session
    val rootNames: List<String> get() = mapping.map.rootNamesFor(settings.value?.launcher ?: Launcher.ESDE)

    fun setLauncher(l: Launcher) = viewModelScope.launch { store.setLauncher(l); mapping.resetAutomatic() }
    fun setRoot(root: StorageRoot) = viewModelScope.launch { store.setStorageRoot(root); mapping.resetAutomatic(); localLibrary.rescanAll() }
    fun setConcurrency(n: Int) = viewModelScope.launch { store.setConcurrency(n) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { store.setWifiOnly(v) }
    fun setExtractZip(v: Boolean) = viewModelScope.launch { store.setAutoExtractZip(v) }
    fun setExtract7z(v: Boolean) = viewModelScope.launch { store.setAutoExtract7z(v) }
    fun setMargin(v: Int) = viewModelScope.launch { store.setScreenMargin(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { store.setThemeMode(v) }
    fun setBiosPath(v: String?) = viewModelScope.launch { store.setBiosPath(v) }
    fun setAllowInsecure(v: Boolean) = viewModelScope.launch { store.setAllowInsecureRemote(v) }
    fun setRemoteUrl(v: String?) = viewModelScope.launch { serverStore.setRemoteUrl(v?.let { com.rommmobile.app.core.network.UrlNormalizer.normalize(it) }) }
    fun setCloudflare(id: String?, secret: String?) = viewModelScope.launch { serverStore.setCloudflareAccess(id, secret) }
    fun rescan() = viewModelScope.launch { localLibrary.rescanAll(); toaster.show("OK") }
    fun logout() = auth.logout()
    fun resetOnboarding() = viewModelScope.launch { store.setOnboardingDone(false) }

    fun changeServer(url: String, onResult: (ApiException?) -> Unit) = viewModelScope.launch {
        auth.probe(url).onSuccess { r -> auth.saveServer(r.url, r.version); onResult(null) }.onFailure { onResult(ApiException.from(it)) }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMappings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLocalLibrary: () -> Unit,
    onOpenFirmware: () -> Unit,
    onOpenControls: () -> Unit,
    onLoggedOut: () -> Unit,
    onRerunOnboarding: () -> Unit,
) {
    val vm: SettingsViewModel = hiltViewModel()
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val server by vm.server.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val scroll = rememberScrollState()

    var dialog by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.section_settings), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).rightStickScroll(scroll).padding(horizontal = layout.padding / 2, vertical = 4.dp)) {

            SectionHeader(stringResource(R.string.settings_server))
            SettingRow(stringResource(R.string.settings_server_lan), subtitle = listOfNotNull(server.baseUrl, server.serverVersion?.let { "RomM $it" }).joinToString(" · "), onClick = { dialog = "server" })
            SettingRow(stringResource(R.string.settings_server_remote), subtitle = server.remoteUrl ?: stringResource(R.string.settings_not_set), onClick = { dialog = "remote" })
            SettingRow(stringResource(R.string.settings_cf_access), subtitle = if (server.cfClientId.isNullOrBlank()) stringResource(R.string.settings_not_set) else server.cfClientId, onClick = { dialog = "cf" })
            val user = (session as? Session.LoggedIn)
            SettingRow(stringResource(R.string.settings_account), subtitle = user?.let { "${it.username ?: "?"} · ${it.mode.name.lowercase()}" } ?: stringResource(R.string.settings_logged_out), onClick = { dialog = "logout" })
            SwitchRow(stringResource(R.string.settings_allow_insecure), s.allowInsecureRemote, vm::setAllowInsecure, subtitle = stringResource(R.string.settings_allow_insecure_hint))

            SectionHeader(stringResource(R.string.settings_launcher_folders))
            SettingRow(stringResource(R.string.settings_launcher), subtitle = launcherLabel(s.launcher), onClick = { dialog = "launcher" })
            SettingRow(stringResource(R.string.settings_root), subtitle = s.storageRoot?.displayName ?: stringResource(R.string.settings_not_set), onClick = { dialog = "root" })
            SettingRow(stringResource(R.string.settings_mappings), subtitle = stringResource(R.string.settings_mappings_hint), onClick = onOpenMappings)
            SettingRow(stringResource(R.string.settings_local_library), subtitle = stringResource(R.string.settings_local_library_hint), onClick = onOpenLocalLibrary)
            SettingRow(stringResource(R.string.settings_rescan), subtitle = stringResource(R.string.settings_rescan_hint), onClick = { vm.rescan() })
            SettingRow(stringResource(R.string.settings_bios), subtitle = s.biosPath ?: stringResource(R.string.settings_bios_default), onClick = onOpenFirmware)
            SettingRow(stringResource(R.string.settings_bios_path), subtitle = s.biosPath ?: stringResource(R.string.settings_bios_default), onClick = { dialog = "bios" })

            SectionHeader(stringResource(R.string.settings_downloads))
            SettingRow(stringResource(R.string.settings_concurrency), subtitle = s.concurrency.toString(), onClick = { dialog = "concurrency" })
            SwitchRow(stringResource(R.string.settings_wifi_only), s.wifiOnly, vm::setWifiOnly)
            SwitchRow(stringResource(R.string.settings_extract_zip), s.autoExtractZip, vm::setExtractZip, subtitle = stringResource(R.string.settings_extract_zip_hint))
            SwitchRow(stringResource(R.string.settings_extract_7z), s.autoExtract7z, vm::setExtract7z, subtitle = stringResource(R.string.settings_extract_7z_hint))
            SettingRow(
                stringResource(R.string.settings_per_platform),
                subtitle = stringResource(R.string.settings_per_platform_hint),
                onClick = { dialog = "perplatform" },
            )

            SectionHeader(stringResource(R.string.settings_controls))
            SettingRow(stringResource(R.string.settings_button_map), subtitle = stringResource(if (s.buttonMap.isDefault) R.string.settings_button_map_default else R.string.settings_button_map_custom), onClick = onOpenControls)
            SettingRow(stringResource(R.string.settings_margin), subtitle = "${s.screenMarginDp} dp", onClick = { dialog = "margin" })

            SectionHeader(stringResource(R.string.settings_appearance))
            SettingRow(stringResource(R.string.settings_theme), subtitle = themeLabel(s.themeMode), onClick = { dialog = "theme" })

            SectionHeader(stringResource(R.string.settings_advanced))
            SettingRow(stringResource(R.string.settings_diagnostics), subtitle = stringResource(R.string.settings_diagnostics_hint), onClick = onOpenDiagnostics)
            SettingRow(stringResource(R.string.settings_rerun_onboarding), onClick = { dialog = "rerun" })
            SettingRow(stringResource(R.string.settings_version), subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Spacer(Modifier.height(24.dp))
        }
    }

    when (dialog) {
        "server" -> {
            var err by remember { mutableStateOf<String?>(null) }
            TextInputDialog(stringResource(R.string.settings_server_lan), server.baseUrl.orEmpty(), error = err, onDismiss = { dialog = null }) { v ->
                vm.changeServer(v) { e -> if (e == null) dialog = null else err = e.userMessage(context) }
            }
        }
        "remote" -> TextInputDialog(stringResource(R.string.settings_server_remote), server.remoteUrl.orEmpty(), hint = "https://romm.example.com", onDismiss = { dialog = null }) { vm.setRemoteUrl(it.ifBlank { null }); dialog = null }
        "cf" -> CloudflareDialog(server.cfClientId.orEmpty(), server.cfClientSecret.orEmpty(), onDismiss = { dialog = null }) { id, secret -> vm.setCloudflare(id.ifBlank { null }, secret.ifBlank { null }); dialog = null }
        "logout" -> RommDialog(onDismissRequest = { dialog = null }, title = stringResource(R.string.settings_logout_title), confirmText = stringResource(R.string.action_logout), onConfirm = { dialog = null; vm.logout(); onLoggedOut() }, destructive = true, focusConfirm = false) {
            Text(stringResource(R.string.settings_logout_body))
        }
        "launcher" -> ChoiceDialog(
            title = stringResource(R.string.settings_launcher),
            options = Launcher.entries.map { launcherLabel(it) to stringResource(launcherDescRes(it)) },
            selected = Launcher.entries.indexOf(s.launcher),
            onDismiss = { dialog = null },
        ) { i -> vm.setLauncher(Launcher.entries[i]); dialog = null }
        "root" -> RootChooserDialog(preferredNames = vm.rootNames, onChosen = { vm.setRoot(it); dialog = null }, onDismiss = { dialog = null })
        "bios" -> TextInputDialog(stringResource(R.string.settings_bios_path), s.biosPath.orEmpty(), hint = "/storage/emulated/0/RetroArch/system", onDismiss = { dialog = null }) { vm.setBiosPath(it.ifBlank { null }); dialog = null }
        "concurrency" -> ChoiceDialog(stringResource(R.string.settings_concurrency), (1..5).map { it.toString() to null }, s.concurrency - 1, onDismiss = { dialog = null }) { vm.setConcurrency(it + 1); dialog = null }
        "margin" -> ChoiceDialog(stringResource(R.string.settings_margin), listOf(0, 4, 8, 12, 16).map { "$it dp" to null }, listOf(0, 4, 8, 12, 16).indexOf(s.screenMarginDp).coerceAtLeast(0), onDismiss = { dialog = null }) { vm.setMargin(listOf(0, 4, 8, 12, 16)[it]); dialog = null }
        "theme" -> ChoiceDialog(stringResource(R.string.settings_theme), ThemeMode.entries.map { themeLabel(it) to null }, ThemeMode.entries.indexOf(s.themeMode), onDismiss = { dialog = null }) { vm.setTheme(ThemeMode.entries[it]); dialog = null }
        "perplatform" -> PerPlatformDialog(vm, s, onDismiss = { dialog = null })
        "rerun" -> RommDialog(onDismissRequest = { dialog = null }, title = stringResource(R.string.settings_rerun_onboarding), confirmText = stringResource(R.string.action_continue), onConfirm = { dialog = null; vm.resetOnboarding(); onRerunOnboarding() }, focusConfirm = false) {
            Text(stringResource(R.string.settings_rerun_body))
        }
    }
}

@Composable
fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    hint: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Uri,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    RommDialog(onDismissRequest = onDismiss, title = title, confirmText = stringResource(R.string.action_save), onConfirm = { onConfirm(value.trim()) }, focusConfirm = false) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            placeholder = hint?.let { { Text(it) } },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small),
        )
    }
}

@Composable
private fun CloudflareDialog(initialId: String, initialSecret: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var id by remember { mutableStateOf(initialId) }
    var secret by remember { mutableStateOf(initialSecret) }
    RommDialog(onDismissRequest = onDismiss, title = stringResource(R.string.settings_cf_access), confirmText = stringResource(R.string.action_save), onConfirm = { onConfirm(id.trim(), secret.trim()) }, focusConfirm = false) {
        Column {
            Text(stringResource(R.string.settings_cf_hint), style = MaterialTheme.typography.bodySmall, color = RommTheme.colors.onSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = id, onValueChange = { id = it }, singleLine = true, label = { Text("CF-Access-Client-Id") }, modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = secret, onValueChange = { secret = it }, singleLine = true, label = { Text("CF-Access-Client-Secret") }, modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small))
        }
    }
}

@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String?>>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val colors = RommTheme.colors
    RommDialog(onDismissRequest = onDismiss, title = title, confirmText = stringResource(R.string.action_close), onConfirm = onDismiss, dismissText = null, focusConfirm = false) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEachIndexed { i, (label, desc) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .gamepadFocusRing(MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onSelect(i) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = i == selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
                    }
                }
            }
        }
    }
}
