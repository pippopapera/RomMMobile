package com.rommmobile.app.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.BuildConfig
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusIcon
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.AuthStore
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DiagnosticsInfo(
    val rows: List<Pair<String, String>> = emptyList(),
    val downloads: List<String> = emptyList(),
    val logTail: List<String> = emptyList(),
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverStore: ServerStore,
    private val authStore: AuthStore,
    private val settings: SettingsStore,
    private val mapping: MappingRepository,
    private val downloads: DownloadRepository,
    private val log: FileLogger,
) : ViewModel() {
    private val _info = MutableStateFlow(DiagnosticsInfo())
    val info: StateFlow<DiagnosticsInfo> = _info.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        val cfg = serverStore.config.value
        val auth = authStore.state.value
        val s = settings.snapshot()
        val gw = mapping.gateway()
        val free = gw?.freeSpaceBytes() ?: -1L
        val writable = runCatching { gw?.isWritable() }.getOrNull()
        val maps = mapping.observeAll().first()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val rows = listOf(
            "App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.SDK_INT} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Server LAN" to (cfg.baseUrl ?: "-"),
            "Server remoto" to (cfg.remoteUrl ?: "-"),
            "Endpoint attivo" to (cfg.activeUrl ?: "-"),
            "Versione RomM" to (cfg.serverVersion ?: "-"),
            "Auth" to "${auth.mode} · ${auth.username ?: "-"}",
            "Cloudflare Access" to (if (cfg.cfClientId.isNullOrBlank()) "no" else "sì"),
            "Launcher" to s.launcher.id,
            "Cartella ROM" to (s.storageRoot?.displayName ?: "-"),
            "All files access" to (if (StorageLocations.hasAllFilesAccess(context)) "concesso" else "NO"),
            "Scrivibile" to (writable?.toString() ?: "-"),
            "Spazio libero" to (if (free >= 0) Format.bytes(free) else "-"),
            "Mapping risolti" to "${maps.size}",
            "Concorrenza / Wi-Fi only" to "${s.concurrency} / ${s.wifiOnly}",
            "Estrazione zip / 7z" to "${s.autoExtractZip} / ${s.autoExtract7z}",
        )
        val dl = downloads.recent(20).map { e -> "${fmt.format(Date(e.updatedAt))}  ${e.state.name.padEnd(11)} ${e.title} (${e.platformSlug}) ${e.error ?: ""}".trimEnd() }
        _info.value = DiagnosticsInfo(rows, dl, log.tail(200))
    }

    fun exportLog(): Intent? = runCatching {
        val file = log.export()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "RomMMobile log")
            putExtra(Intent.EXTRA_TEXT, _info.value.rows.joinToString("\n") { "${it.first}: ${it.second}" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val vm: DiagnosticsViewModel = hiltViewModel()
    val info by vm.info.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = RommTheme.colors
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.settings_diagnostics), onBack = onBack) {
            IconButton(onClick = { vm.refresh() }, modifier = Modifier.gamepadFocusIcon()) { Icon(Icons.Rounded.Refresh, stringResource(R.string.action_refresh)) }
            IconButton(onClick = { vm.exportLog()?.let { context.startActivity(Intent.createChooser(it, null)) } }, modifier = Modifier.gamepadFocusIcon()) {
                Icon(Icons.Rounded.Share, stringResource(R.string.action_export_log))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).rightStickScroll(scroll)) {
            SectionHeader(stringResource(R.string.diag_state))
            info.rows.forEach { (k, v) -> SettingRow(title = k, subtitle = v) }
            SectionHeader(stringResource(R.string.diag_downloads))
            if (info.downloads.isEmpty()) Text("-", modifier = Modifier.padding(horizontal = 14.dp), color = colors.onSurfaceMuted)
            info.downloads.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 1.dp)) }
            SectionHeader(stringResource(R.string.diag_log))
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                info.logTail.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, color = if (" E/" in it) colors.red else colors.onSurfaceMuted) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
