package com.rommmobile.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.compose.BackHandler
import com.rommmobile.app.R
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusIcon
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.db.LocalFileEntity
import com.rommmobile.app.data.db.LocalSummaryRow
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.PlatformRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val local: LocalLibraryRepository,
    platforms: PlatformRepository,
    private val toaster: Toaster,
) : ViewModel() {
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    val summary: StateFlow<List<Pair<LocalSummaryRow, String>>> = combine(local.summary(), platforms.platforms) { rows, plats ->
        val names = plats.associate { it.slug to it.name }
        rows.sortedBy { names[it.platformSlug] ?: it.platformSlug }.map { it to (names[it.platformSlug] ?: it.platformSlug) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val files: StateFlow<List<LocalFileEntity>> = _selected.flatMapLatest { slug -> if (slug == null) flowOf(emptyList()) else local.files(slug) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(slug: String?) { _selected.value = slug }
    fun rescan() = viewModelScope.launch { local.rescanAll() }
    fun delete(entry: LocalFileEntity) = viewModelScope.launch {
        if (!local.deleteEntry(entry)) toaster.show("Impossibile eliminare", isError = true)
    }
}

@Composable
fun LocalLibraryScreen(onBack: () -> Unit) {
    val vm: LocalLibraryViewModel = hiltViewModel()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    // The top-bar arrow steps out of the platform first; B has to do the same.
    BackHandler(enabled = selected != null) { vm.select(null) }
    val files by vm.files.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    val listState = rememberLazyListState()
    var confirmDelete by remember { mutableStateOf<LocalFileEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        val title = selected?.let { s -> summary.firstOrNull { it.first.platformSlug == s }?.second } ?: stringResource(R.string.settings_local_library)
        RommTopBar(
            title = title,
            subtitle = if (selected == null) stringResource(R.string.local_total, summary.sumOf { it.first.count }, Format.bytes(summary.sumOf { it.first.bytes })) else null,
            onBack = { if (selected != null) vm.select(null) else onBack() },
        ) {
            IconButton(onClick = { vm.rescan() }, modifier = Modifier.gamepadFocusIcon()) { Icon(Icons.Rounded.Refresh, stringResource(R.string.action_rescan)) }
        }
        if (selected == null) {
            if (summary.isEmpty()) EmptyState(Icons.Rounded.FolderOpen, stringResource(R.string.local_empty), subtitle = stringResource(R.string.local_empty_hint))
            else LazyColumn(state = listState, modifier = Modifier.fillMaxSize().rightStickScroll(listState), contentPadding = PaddingValues(4.dp)) {
                items(summary, key = { it.first.platformSlug }) { (row, name) ->
                    SettingRow(title = name, subtitle = pluralStringResource(R.plurals.n_files, row.count, row.count) + " · " + Format.bytes(row.bytes), onClick = { vm.select(row.platformSlug) }) {
                        Icon(Icons.Rounded.Folder, null, tint = colors.primaryLighten)
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().rightStickScroll(listState), contentPadding = PaddingValues(4.dp)) {
                items(files, key = { it.fileName }) { f ->
                    SettingRow(
                        title = f.fileName,
                        subtitle = (if (f.isDirectory) stringResource(R.string.local_folder) else Format.bytes(f.sizeBytes)) + if (f.downloadedByApp) " · " + stringResource(R.string.local_by_app) else "",
                        onClick = if (f.downloadedByApp) ({ confirmDelete = f }) else null,
                    ) {
                        if (f.downloadedByApp) Icon(Icons.Rounded.Delete, null, tint = colors.onSurfaceMuted)
                    }
                }
            }
        }
    }

    confirmDelete?.let { f ->
        RommDialog(onDismissRequest = { confirmDelete = null }, title = stringResource(R.string.local_delete_title), confirmText = stringResource(R.string.action_delete), onConfirm = { vm.delete(f); confirmDelete = null }, destructive = true, focusConfirm = false) {
            Text(stringResource(R.string.local_delete_body, f.fileName))
        }
    }
}
