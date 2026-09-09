package com.rommmobile.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.compose.BackHandler
import com.rommmobile.app.R
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.ErrorState
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.apiCall
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.api.FirmwareDto
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.DownloadRepository
import com.rommmobile.app.data.repo.EnqueueResult
import com.rommmobile.app.data.repo.Platform
import com.rommmobile.app.data.repo.PlatformRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class FirmwareItem(val dto: FirmwareDto, val present: Boolean)

@HiltViewModel
class FirmwareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    platforms: PlatformRepository,
    private val api: RommApi,
    private val settings: SettingsStore,
    private val downloads: DownloadRepository,
    private val toaster: Toaster,
) : ViewModel() {
    val platforms: StateFlow<List<Platform>> = platforms.platforms.map { l -> l.filter { it.firmwareCount > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Platform?>(null)
    val selected: StateFlow<Platform?> = _selected.asStateFlow()
    private val _items = MutableStateFlow<List<FirmwareItem>?>(null)
    val items: StateFlow<List<FirmwareItem>?> = _items.asStateFlow()
    private val _error = MutableStateFlow<ApiException?>(null)
    val error: StateFlow<ApiException?> = _error.asStateFlow()
    private val _biosDir = MutableStateFlow<String?>(null)
    val biosDir: StateFlow<String?> = _biosDir.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.snapshot()
            val root = s.storageRoot as? StorageRoot.Direct
            _biosDir.value = s.biosPath?.takeIf { it.isNotBlank() } ?: root?.let { it.path.substringBeforeLast('/') + "/RetroArch/system" }
        }
    }

    fun select(p: Platform?) {
        _selected.value = p
        _items.value = null
        if (p != null) load(p)
    }

    private fun load(p: Platform) = viewModelScope.launch {
        apiCall { api.firmware(p.id) }.onSuccess { list ->
            val dir = _biosDir.value
            _items.value = list.map { f -> FirmwareItem(f, dir != null && File(dir, f.fileName).exists()) }
            _error.value = null
        }.onFailure { _error.value = ApiException.from(it) }
    }

    fun download(item: FirmwareItem) = viewModelScope.launch {
        val p = _selected.value ?: return@launch
        when (val r = downloads.enqueueFirmware(item.dto.id, item.dto.fileName, item.dto.fileSizeBytes, item.dto.md5Hash, item.dto.sha1Hash, item.dto.crcHash, p.name)) {
            is EnqueueResult.Enqueued -> toaster.show(context.getString(R.string.toast_enqueued, r.relDir))
            EnqueueResult.AlreadyPresent -> toaster.show(context.getString(R.string.bios_already_present))
            EnqueueResult.AlreadyQueued -> toaster.show(context.getString(R.string.toast_already_queued))
            EnqueueResult.NoRoot -> toaster.show(context.getString(R.string.bios_needs_direct), isError = true)
            else -> toaster.show(context.getString(R.string.error_unknown, ""), isError = true)
        }
    }
}

@Composable
fun FirmwareScreen(onBack: () -> Unit) {
    val vm: FirmwareViewModel = hiltViewModel()
    val platforms by vm.platforms.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    // The top-bar arrow steps out of the platform first; B has to do the same.
    BackHandler(enabled = selected != null) { vm.select(null) }
    val items by vm.items.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val biosDir by vm.biosDir.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    val context = LocalContext.current
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = selected?.name ?: stringResource(R.string.settings_bios), subtitle = biosDir ?: stringResource(R.string.bios_needs_direct), onBack = { if (selected != null) vm.select(null) else onBack() })
        val p = selected
        when {
            p == null -> {
                if (platforms.isEmpty()) EmptyState(Icons.Rounded.Memory, stringResource(R.string.bios_empty), subtitle = stringResource(R.string.bios_empty_hint))
                else LazyColumn(state = listState, modifier = Modifier.fillMaxSize().rightStickScroll(listState), contentPadding = PaddingValues(4.dp)) {
                    item { Text(stringResource(R.string.bios_intro), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
                    items(platforms, key = { it.id }) { plat ->
                        SettingRow(title = plat.name, subtitle = stringResource(R.string.bios_count, plat.firmwareCount), onClick = { vm.select(plat) }) { Icon(Icons.Rounded.Memory, null, tint = colors.primaryLighten) }
                    }
                }
            }
            error != null && items == null -> ErrorState(error!!.userMessage(context), onRetry = { vm.select(p) })
            items == null -> Text(stringResource(R.string.loading), modifier = Modifier.padding(14.dp), color = colors.onSurfaceMuted)
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().rightStickScroll(listState), contentPadding = PaddingValues(4.dp)) {
                items(items!!, key = { it.dto.id }) { item ->
                    SettingRow(
                        title = item.dto.fileName,
                        subtitle = Format.bytes(item.dto.fileSizeBytes) + (item.dto.md5Hash?.let { " · md5 ${it.take(8)}" } ?: "") + if (item.present) " · " + stringResource(R.string.state_present) else "",
                        onClick = { vm.download(item) },
                    ) {
                        Icon(if (item.present) Icons.Rounded.Check else Icons.Rounded.Download, null, tint = if (item.present) colors.green else colors.primaryLighten)
                    }
                }
            }
        }
    }
}
