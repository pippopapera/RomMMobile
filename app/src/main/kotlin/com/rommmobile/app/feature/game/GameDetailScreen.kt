package com.rommmobile.app.feature.game

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rommmobile.app.R
import com.rommmobile.app.core.design.Badge
import com.rommmobile.app.core.design.CoverImage
import com.rommmobile.app.core.design.ErrorState
import com.rommmobile.app.core.design.PadBar
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.Skeleton
import com.rommmobile.app.core.design.StatusPill
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.input.GamepadAction
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.repo.DownloadRepository
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.data.repo.RomRepository
import com.rommmobile.app.feature.downloads.DownloadMiniBar
import com.rommmobile.app.feature.downloads.EnqueueController
import com.rommmobile.app.feature.downloads.FolderDialog
import com.rommmobile.app.feature.downloads.RedownloadDialog
import com.rommmobile.app.feature.downloads.engine.DownloadProgress
import com.rommmobile.app.feature.main.GameRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface GameUiState {
    data object Loading : GameUiState
    data class Error(val error: ApiException) : GameUiState
    data class Ready(val rom: Rom) : GameUiState
}

@HiltViewModel
class GameViewModel @Inject constructor(
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val roms: RomRepository,
    private val local: LocalLibraryRepository,
    private val toaster: Toaster,
    downloads: DownloadRepository,
    val enqueue: EnqueueController,
) : ViewModel() {
    private val romId = handle.toRoute<GameRoute>().id

    private val _state = MutableStateFlow<GameUiState>(GameUiState.Loading)
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    val present: StateFlow<Boolean> = _state.flatMapLatest { s ->
        val rom = (s as? GameUiState.Ready)?.rom
        if (rom == null) MutableStateFlow(false) else local.presenceKeys(rom.platformSlug).map { rom.nameKey in it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val progress: StateFlow<DownloadProgress?> = combine(downloads.observeOpenForRom(romId), downloads.progress) { open, prog ->
        open.firstOrNull()?.let { e -> prog[e.id] ?: DownloadProgress(e.id, e.downloadedBytes, e.totalBytes, 0, e.state) }
    }.sample(250).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { load() }

    fun load() = viewModelScope.launch {
        // Show the cached record instantly, then replace it with the fresh one.
        roms.cached(romId)?.let { _state.value = GameUiState.Ready(it) }
        roms.detail(romId).onSuccess { _state.value = GameUiState.Ready(it) }.onFailure { e ->
            if (_state.value !is GameUiState.Ready) _state.value = GameUiState.Error(ApiException.from(e))
        }
    }

    fun download(force: Boolean = false) = viewModelScope.launch {
        (state.value as? GameUiState.Ready)?.rom?.let { enqueue.enqueue(it, force) }
    }

    /** Removes the game from this device only. The copy on RomM is never touched. */
    fun uninstall() = viewModelScope.launch {
        val rom = (state.value as? GameUiState.Ready)?.rom ?: return@launch
        val gone = local.deleteGame(rom.platformSlug, rom.nameKey)
        toaster.show(
            if (gone > 0) context.getString(R.string.uninstall_done) else context.getString(R.string.uninstall_failed),
            isError = gone == 0,
        )
    }
}

@Composable
fun GameDetailScreen(romId: Int, onBack: () -> Unit, onOpenGame: (Int) -> Unit, onOpenDownloads: () -> Unit) {
    val vm: GameViewModel = hiltViewModel()
    var confirmUninstall by remember { mutableStateOf<Rom?>(null) }
    val state by vm.state.collectAsStateWithLifecycle()
    val present by vm.present.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val pending by vm.enqueue.pending.collectAsStateWithLifecycle()
    val redownload by vm.enqueue.confirmRedownload.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val layout = RommTheme.layout

    Column(Modifier.fillMaxSize()) {
        val title = (state as? GameUiState.Ready)?.rom?.name ?: ""
        RommTopBar(title = title, subtitle = (state as? GameUiState.Ready)?.rom?.platformName, onBack = onBack)
        Box(Modifier.weight(1f)) {
            when (val s = state) {
                GameUiState.Loading -> DetailSkeleton()
                is GameUiState.Error -> ErrorState(s.error.userMessage(context), onRetry = { vm.load() })
                is GameUiState.Ready -> DetailBody(
                    rom = s.rom,
                    present = present,
                    progress = progress,
                    horizontal = layout.isShort || layout.widthDp >= 600,
                    onDownload = { vm.download() },
                    onDownloadAgain = { vm.download(force = true) },
                    onUninstall = { confirmUninstall = s.rom },
                    onOpenGame = onOpenGame,
                )
            }
        }
        DownloadMiniBar(onClick = onOpenDownloads)
        // No shortcut bar here. The action button under the cover holds the focus and says what A
        // does, and a second "X Download" next to it was the duplicate the owner keeps removing.
        // R3 still has to be handled, because MainScaffold is not resumed behind this destination.
        PadBar { silent(GamepadAction.OPEN_DOWNLOADS) { onOpenDownloads() } }
    }

    pending?.let { p ->
        FolderDialog(
            pending = p,
            onCreate = { name -> vm.viewModelScope.launch { vm.enqueue.confirmCreate(name) } },
            onChoose = { dir -> vm.viewModelScope.launch { vm.enqueue.chooseCandidate(dir) } },
            onDismiss = vm.enqueue::dismiss,
        )
    }
    redownload?.let { rom ->
        RedownloadDialog(rom = rom, onConfirm = { vm.viewModelScope.launch { vm.enqueue.confirmRedownload() } }, onDismiss = vm.enqueue::dismissRedownload)
    }
    confirmUninstall?.let { rom ->
        UninstallDialog(rom = rom, onConfirm = { confirmUninstall = null; vm.uninstall() }, onDismiss = { confirmUninstall = null })
    }
}

/** Deleting a file cannot be undone, so it is always confirmed, and the wording says what survives. */
@Composable
fun UninstallDialog(rom: Rom, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RommDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.uninstall_title),
        confirmText = stringResource(R.string.action_uninstall),
        onConfirm = onConfirm,
        destructive = true,
    ) {
        Text(stringResource(R.string.uninstall_body, rom.name), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DetailBody(
    rom: Rom,
    present: Boolean,
    progress: DownloadProgress?,
    horizontal: Boolean,
    onDownload: () -> Unit,
    onDownloadAgain: () -> Unit,
    onUninstall: () -> Unit,
    onOpenGame: (Int) -> Unit,
) {
    val layout = RommTheme.layout
    val scroll = rememberScrollState()
    val cover: @Composable () -> Unit = {
        Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f / 1.4f)) {
            CoverImage(rom.coverLarge ?: rom.coverSmall, rom.name, Modifier.fillMaxSize())
            rom.regionBadge?.let { Badge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
        }
    }
    val info: @Composable () -> Unit = {
        InfoColumn(rom, present, progress, onDownload, onDownloadAgain, onUninstall, onOpenGame)
    }
    if (horizontal) {
        Row(Modifier.fillMaxSize().padding(layout.padding)) {
            Column(Modifier.width(if (layout.isShort) 150.dp else 220.dp).fillMaxHeight()) { cover() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).rightStickScroll(scroll)) { info() }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).rightStickScroll(scroll).padding(layout.padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(200.dp)) { cover() }
            Spacer(Modifier.height(12.dp))
            info()
        }
    }
}

@Composable
private fun InfoColumn(
    rom: Rom,
    present: Boolean,
    progress: DownloadProgress?,
    onDownload: () -> Unit,
    onDownloadAgain: () -> Unit,
    onUninstall: () -> Unit,
    onOpenGame: (Int) -> Unit,
) {
    val colors = RommTheme.colors
    val downloadFr = remember { FocusRequester() }
    // requestFocus() does not report failure, it just quietly does nothing while the screen is
    // still laying out - which is why the action button used to sit there looking exactly like a
    // button nobody had selected. Retry until the button itself says it has focus.
    var actionFocused by remember { mutableStateOf(false) }
    // requestFocus() reports nothing when it fails, and in touch mode it is a no-op by design, so
    // keep asking for a few frames and stop as soon as the button itself says it has focus. Landing
    // here with the pad puts the action under the cursor; landing here with a finger does not, and
    // should not.
    LaunchedEffect(rom.id) {
        repeat(10) {
            if (actionFocused) return@LaunchedEffect
            runCatching { downloadFr.requestFocus() }
            kotlinx.coroutines.delay(60)
        }
    }

    Text(rom.name, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(4.dp))
    val meta = buildList {
        add(rom.platformName)
        Format.year(rom.firstReleaseDate)?.let { add(it) }
        rom.rating?.takeIf { it > 0 }?.let { add("★ ${"%.1f".format(if (it > 10) it / 10.0 else it)}") }
        if (rom.sizeBytes > 0) add(Format.bytes(rom.sizeBytes))
    }
    Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
    if (rom.genres.isNotEmpty() || rom.companies.isNotEmpty()) {
        Spacer(Modifier.height(2.dp))
        Text((rom.genres + rom.companies).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(12.dp))

    // ---- action row ----
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            progress != null && !progress.state.isTerminal -> {
                val label = when (progress.state) {
                    DownloadState.PENDING -> stringResource(R.string.state_pending)
                    DownloadState.PAUSED -> stringResource(R.string.state_paused)
                    DownloadState.DOWNLOADING -> stringResource(R.string.state_downloading_pct, progress.percent)
                    DownloadState.VERIFYING -> stringResource(R.string.state_verifying)
                    DownloadState.EXTRACTING -> stringResource(R.string.state_extracting)
                    DownloadState.MOVING -> stringResource(R.string.state_moving)
                    else -> progress.state.name
                }
                StatusPill(label, colors.blue)
            }
            present -> {
                StatusPill(stringResource(R.string.state_present), colors.green)
                // The game is already here, so the useful action is getting rid of it. Downloading
                // it a second time stays reachable but reads as secondary, because it almost never
                // is what the user came for.
                Button(
                    onClick = onUninstall,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.red, contentColor = Color.White),
                    modifier = Modifier.focusRequester(downloadFr).onFocusChanged { actionFocused = it.isFocused }.gamepadFocusRing(PillShape),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_uninstall))
                }
                TextButton(onClick = onDownloadAgain, modifier = Modifier.gamepadFocusRing(PillShape)) {
                    Text(stringResource(R.string.action_download_again))
                }
            }
            else -> Button(onClick = onDownload, modifier = Modifier.focusRequester(downloadFr).onFocusChanged { actionFocused = it.isFocused }.gamepadFocusRing(PillShape)) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_download))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(rom.fsName, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
    if (rom.hasMultipleFiles) {
        Text(stringResource(R.string.detail_multi_file, rom.files.size), style = MaterialTheme.typography.bodySmall, color = colors.accent)
    }

    if (!rom.summary.isNullOrBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(rom.summary, style = MaterialTheme.typography.bodyMedium)
    }

    if (rom.siblings.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.detail_versions, rom.siblings.size + 1), Modifier.padding(0.dp))
        rom.siblings.forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .gamepadFocusRing(MaterialTheme.shapes.small)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onOpenGame(s.id) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.fsNameNoTags ?: s.name ?: "#${s.id}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (s.isMain) Icon(Icons.Rounded.Check, null, tint = colors.primaryLighten, modifier = Modifier.size(16.dp))
            }
        }
    }

    if (rom.files.size > 1) {
        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.detail_files, rom.files.size), Modifier.padding(0.dp))
        rom.files.take(40).forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 6.dp)) {
                Text(f.fileName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Format.bytes(f.sizeBytes), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            }
        }
        if (rom.files.size > 40) Text("…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, modifier = Modifier.padding(horizontal = 6.dp))
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun DetailSkeleton() {
    val layout = RommTheme.layout
    Row(Modifier.fillMaxSize().padding(layout.padding)) {
        Skeleton(Modifier.width(150.dp).aspectRatio(1f / 1.4f))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Skeleton(Modifier.fillMaxWidth(0.7f).height(22.dp), MaterialTheme.shapes.small)
            Spacer(Modifier.height(8.dp))
            Skeleton(Modifier.fillMaxWidth(0.4f).height(14.dp), MaterialTheme.shapes.small)
            Spacer(Modifier.height(16.dp))
            Skeleton(Modifier.width(140.dp).height(36.dp), PillShape)
            Spacer(Modifier.height(16.dp))
            repeat(4) { Skeleton(Modifier.fillMaxWidth().height(12.dp).padding(bottom = 6.dp), MaterialTheme.shapes.small); Spacer(Modifier.height(6.dp)) }
        }
    }
}
