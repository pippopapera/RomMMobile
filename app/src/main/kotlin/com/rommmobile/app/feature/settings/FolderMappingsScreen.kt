package com.rommmobile.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusIcon
import com.rommmobile.app.core.design.gamepadTextField
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.data.db.MappingSource
import com.rommmobile.app.data.mapping.FolderMapping
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.mapping.Resolution
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.Platform
import com.rommmobile.app.data.repo.PlatformRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class MappingStatus { FOUND, TO_CREATE, TO_CHOOSE, UNKNOWN }

@Immutable
data class MappingRow(val platform: Platform, val mapping: FolderMapping?, val status: MappingStatus, val proposal: String?, val candidates: List<String>)

@HiltViewModel
class FolderMappingsViewModel @Inject constructor(
    private val platforms: PlatformRepository,
    private val mapping: MappingRepository,
    private val settings: SettingsStore,
    private val localLibrary: LocalLibraryRepository,
) : ViewModel() {
    private val resolutions = MutableStateFlow<Map<String, Resolution>>(emptyMap())
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _rootDirs = MutableStateFlow<List<String>>(emptyList())
    val rootDirs: StateFlow<List<String>> = _rootDirs.asStateFlow()

    val rows: StateFlow<List<MappingRow>> = combine(platforms.platforms, mapping.observeAll(), resolutions) { plats, maps, res ->
        val byslug = maps.associateBy { it.platformSlug }
        plats.map { p ->
            val m = byslug[p.slug]
            val r = res[p.slug]
            when {
                m != null -> MappingRow(p, m, MappingStatus.FOUND, null, emptyList())
                r is Resolution.NeedsCreate -> MappingRow(p, null, MappingStatus.TO_CREATE, r.proposedName, emptyList())
                r is Resolution.Ambiguous -> MappingRow(p, null, MappingStatus.TO_CHOOSE, r.preselected, r.candidates.map { it.relDir })
                else -> MappingRow(p, null, MappingStatus.UNKNOWN, null, emptyList())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { discover() }

    fun discover() = viewModelScope.launch {
        _busy.value = true
        val plats = platforms.platforms.first()
        val results = mapping.discoverAll(plats)
        resolutions.value = results.associate { (p, r) -> p.slug to r }
        mapping.refreshCounts()
        _rootDirs.value = withContext(Dispatchers.IO) { runCatching { mapping.gateway()?.listDirs("")?.sorted() }.getOrNull() ?: emptyList() }
        _busy.value = false
    }

    fun setUser(slug: String, relDir: String) = viewModelScope.launch { mapping.setUserMapping(slug, relDir); localLibrary.rescan(slug) }
    fun create(slug: String, name: String) = viewModelScope.launch { mapping.createFolder(slug, name); localLibrary.rescan(slug) }
    fun clear(slug: String) = viewModelScope.launch { mapping.clear(slug); discover() }
}

@Composable
fun FolderMappingsScreen(onBack: () -> Unit) {
    val vm: FolderMappingsViewModel = hiltViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val rootDirs by vm.rootDirs.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<MappingRow?>(null) }

    Column(Modifier.fillMaxSize()) {
        RommTopBar(
            title = stringResource(R.string.settings_mappings),
            subtitle = stringResource(R.string.mappings_subtitle, rows.count { it.status == MappingStatus.FOUND }, rows.size),
            onBack = onBack,
        ) {
            IconButton(onClick = { vm.discover() }, enabled = !busy, modifier = Modifier.gamepadFocusIcon()) { Icon(Icons.Rounded.Refresh, stringResource(R.string.action_rescan)) }
        }
        if (rows.isEmpty()) {
            EmptyState(Icons.Rounded.CreateNewFolder, stringResource(R.string.mappings_empty))
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().rightStickScroll(listState), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                items(rows, key = { it.platform.id }) { row ->
                    val (icon, tint, text) = when (row.status) {
                        MappingStatus.FOUND -> Triple(Icons.Rounded.CheckCircle, colors.green, row.mapping!!.relDir + " · " + pluralStringResource(R.plurals.n_files, row.mapping.fileCount, row.mapping.fileCount) + sourceSuffix(row.mapping.source))
                        MappingStatus.TO_CREATE -> Triple(Icons.Rounded.CreateNewFolder, colors.accent, stringResource(R.string.mappings_to_create, row.proposal ?: row.platform.slug))
                        MappingStatus.TO_CHOOSE -> Triple(Icons.Rounded.Edit, colors.blue, stringResource(R.string.mappings_to_choose, row.candidates.joinToString(", ")))
                        MappingStatus.UNKNOWN -> Triple(Icons.Rounded.Edit, colors.gray, stringResource(R.string.mappings_unknown))
                    }
                    SettingRow(title = row.platform.name, subtitle = text, onClick = { editing = row }) {
                        Icon(icon, null, tint = tint)
                    }
                }
            }
        }
    }

    editing?.let { row ->
        MappingEditDialog(
            row = row,
            rootDirs = rootDirs,
            onChoose = { vm.setUser(row.platform.slug, it); editing = null },
            onCreate = { vm.create(row.platform.slug, it); editing = null },
            onClear = { vm.clear(row.platform.slug); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun sourceSuffix(source: MappingSource): String = when (source) {
    MappingSource.USER -> " · " + stringResource(R.string.mapping_source_user)
    MappingSource.CREATED -> " · " + stringResource(R.string.mapping_source_created)
    else -> ""
}

@Composable
fun MappingEditDialog(
    row: MappingRow,
    rootDirs: List<String>,
    onChoose: (String) -> Unit,
    onCreate: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RommTheme.colors
    var newName by remember { mutableStateOf(row.proposal ?: row.platform.slug) }
    RommDialog(
        onDismissRequest = onDismiss,
        title = row.platform.name,
        confirmText = stringResource(R.string.folder_create_confirm),
        onConfirm = { onCreate(newName.trim()) },
        focusConfirm = false,
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.mapping_edit_existing), style = MaterialTheme.typography.titleSmall)
            val preferred = (row.candidates + listOfNotNull(row.mapping?.relDir)).toSet()
            val ordered = rootDirs.sortedByDescending { it in preferred }
            ordered.take(60).forEach { dir ->
                Row(
                    Modifier.fillMaxWidth().gamepadFocusRing(MaterialTheme.shapes.small).clip(MaterialTheme.shapes.small).clickable { onChoose(dir) }.padding(vertical = 5.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(dir, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (dir == row.mapping?.relDir) Icon(Icons.Rounded.CheckCircle, null, tint = colors.green, modifier = Modifier.width(18.dp))
                    else if (dir in preferred) Text(stringResource(R.string.mapping_suggested), style = MaterialTheme.typography.labelSmall, color = colors.primaryLighten)
                }
            }
            Spacer(Modifier.padding(6.dp))
            Text(stringResource(R.string.mapping_edit_create), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small))
            if (row.mapping != null) {
                Spacer(Modifier.padding(6.dp))
                SettingRow(stringResource(R.string.mapping_remove), subtitle = stringResource(R.string.mapping_remove_hint), onClick = onClear)
            }
        }
    }
}
