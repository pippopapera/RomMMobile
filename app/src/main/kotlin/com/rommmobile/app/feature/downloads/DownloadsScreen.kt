package com.rommmobile.app.feature.downloads

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.CoverImage
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.PadBar
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusIcon
import com.rommmobile.app.core.design.gamepadFocusRow
import com.rommmobile.app.core.input.GamepadAction
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.db.DownloadEntity
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.repo.DownloadRepository
import com.rommmobile.app.feature.downloads.engine.DownloadProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Opening a folder has no guaranteed handler on Android. Try the file managers that do accept
 * it, and fall back to putting the path on the clipboard so it is at least usable.
 */
private fun openFolder(context: Context, resultPath: String, toaster: Toaster) {
    val dir = java.io.File(resultPath).let { if (it.isDirectory) it else it.parentFile } ?: return
    val uri = android.net.Uri.parse(
        "content://com.android.externalstorage.documents/document/primary%3A" +
            android.net.Uri.encode(dir.absolutePath.removePrefix("/storage/emulated/0/"))
    )
    val intents = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "resource/folder"),
    )
    for (i in intents) {
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(i); true }.getOrDefault(false)) return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("path", dir.absolutePath))
    toaster.show(context.getString(R.string.toast_path_copied, dir.absolutePath))
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repo: DownloadRepository,
    val toaster: Toaster,
) : ViewModel() {
    val items: StateFlow<List<DownloadEntity>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress: StateFlow<Map<Long, DownloadProgress>> = repo.progress.sample(250).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun pause(id: Long) = repo.pause(id)
    fun resume(id: Long) = repo.resume(id)
    fun cancel(id: Long) = repo.cancel(id)
    fun retry(id: Long) = repo.retry(id)
    fun remove(id: Long) = repo.remove(id)
    fun pauseAll() = repo.pauseAll()
    fun resumeAll() = repo.resumeAll()
    fun clearFinished() = repo.clearFinished()
}

/** Human message for the short error codes stored by the engine. */
fun downloadErrorText(context: Context, code: String?): String = when {
    code == null -> ""
    code == "no_root" -> context.getString(R.string.dl_err_no_root)
    code == "no_space" -> context.getString(R.string.dl_err_no_space)
    code == "hash_mismatch" -> context.getString(R.string.dl_err_hash)
    code == "truncated" || code == "range_reset" -> context.getString(R.string.dl_err_truncated)
    code == "move_failed" || code == "move_size_mismatch" || code == "cannot_create_dir" -> context.getString(R.string.dl_err_move)
    code == "http_401" || code == "http_403" -> context.getString(R.string.dl_err_auth)
    code == "http_404" -> context.getString(R.string.dl_err_not_found)
    code.startsWith("http_") -> context.getString(R.string.dl_err_server, code.removePrefix("http_"))
    else -> context.getString(R.string.dl_err_generic, code)
}

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val vm: DownloadsViewModel = hiltViewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val ctx = LocalContext.current
    var menuFor by remember { mutableStateOf<DownloadEntity?>(null) }
    var focused by remember { mutableStateOf<DownloadEntity?>(null) }

    val active = items.filter { it.state.isActive }
    val waiting = items.filter { it.state == DownloadState.PENDING || it.state == DownloadState.PAUSED }
    val done = items.filter { it.state == DownloadState.COMPLETED || it.state == DownloadState.SKIPPED }
    val failed = items.filter { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED }
    val anyPaused = waiting.any { it.state == DownloadState.PAUSED }
    val anyOpen = active.isNotEmpty() || waiting.any { it.state == DownloadState.PENDING }

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.section_downloads), subtitle = if (items.isEmpty()) null else stringResource(R.string.dl_subtitle, active.size, waiting.size), onBack = onBack) {
            if (anyOpen) IconButton(onClick = { vm.pauseAll() }, modifier = Modifier.gamepadFocusIcon()) { Icon(Icons.Rounded.Pause, stringResource(R.string.action_pause_all)) }
            if (anyPaused) IconButton(onClick = { vm.resumeAll() }, modifier = Modifier.gamepadFocusIcon()) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.action_resume_all)) }
            if (done.isNotEmpty() || failed.isNotEmpty()) IconButton(onClick = { vm.clearFinished() }, modifier = Modifier.gamepadFocusIcon()) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.action_clear_finished)) }
        }
        if (items.isEmpty()) {
            EmptyState(Icons.Rounded.Download, stringResource(R.string.dl_empty), subtitle = stringResource(R.string.dl_empty_hint), modifier = Modifier.weight(1f))
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).rightStickScroll(listState), contentPadding = PaddingValues(vertical = 4.dp)) {
                section(R.string.dl_section_active, active, progress, vm, onFocus = { focused = it }, onMenu = { menuFor = it })
                section(R.string.dl_section_waiting, waiting, progress, vm, onFocus = { focused = it }, onMenu = { menuFor = it })
                section(R.string.dl_section_failed, failed, progress, vm, onFocus = { focused = it }, onMenu = { menuFor = it })
                section(R.string.dl_section_done, done, progress, vm, onFocus = { focused = it }, onMenu = { menuFor = it })
            }
        }
        // A is not advertised: every row carries its own pause / play / retry button.
        // Resolved against the live list: a row that was just cleared must not keep a menu, and an
        // empty queue must not advertise one.
        val focusedRow = focused?.let { f -> items.firstOrNull { it.id == f.id } }
        PadBar(focusedRow?.id) {
            if (focusedRow != null) bind(GamepadAction.CONTEXT_MENU, R.string.hint_more) { menuFor = focusedRow }
        }
    }

    menuFor?.let { e ->
        RommDialog(onDismissRequest = { menuFor = null }, title = e.title, confirmText = stringResource(R.string.action_close), onConfirm = { menuFor = null }, dismissText = null, focusConfirm = false) {
            Column {
                Text(e.fileName, style = MaterialTheme.typography.bodySmall, color = RommTheme.colors.onSurfaceMuted)
                e.resultPath?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RommTheme.colors.onSurfaceMuted) }
                Spacer(Modifier.height(6.dp))
                when (e.state) {
                    DownloadState.PENDING, DownloadState.DOWNLOADING -> SettingRow(stringResource(R.string.action_pause), onClick = { vm.pause(e.id); menuFor = null })
                    DownloadState.PAUSED -> SettingRow(stringResource(R.string.action_resume), onClick = { vm.resume(e.id); menuFor = null })
                    DownloadState.FAILED, DownloadState.CANCELLED -> SettingRow(stringResource(R.string.action_retry), onClick = { vm.retry(e.id); menuFor = null })
                    else -> {}
                }
                if (!e.state.isTerminal) SettingRow(stringResource(R.string.action_cancel_download), onClick = { vm.cancel(e.id); menuFor = null })
                val resultPath = e.resultPath
                if (resultPath != null) {
                    val label = stringResource(R.string.action_open_folder)
                    SettingRow(label, onClick = { openFolder(ctx, resultPath, vm.toaster) })
                }
                SettingRow(stringResource(R.string.action_remove_from_list), onClick = { vm.remove(e.id); menuFor = null })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    titleRes: Int,
    list: List<DownloadEntity>,
    progress: Map<Long, DownloadProgress>,
    vm: DownloadsViewModel,
    onFocus: (DownloadEntity) -> Unit,
    onMenu: (DownloadEntity) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h$titleRes") { SectionHeader(stringResource(titleRes)) }
    items(list, key = { it.id }, contentType = { "dl" }) { e ->
        DownloadRow(e, progress[e.id], onFocus = { onFocus(e) }, onMenu = { onMenu(e) }, onPrimary = {
            when (e.state) {
                DownloadState.PENDING, DownloadState.DOWNLOADING -> vm.pause(e.id)
                DownloadState.PAUSED -> vm.resume(e.id)
                DownloadState.FAILED, DownloadState.CANCELLED -> vm.retry(e.id)
                else -> onMenu(e)
            }
        })
    }
}

@Composable
private fun DownloadRow(e: DownloadEntity, p: DownloadProgress?, onFocus: () -> Unit, onMenu: () -> Unit, onPrimary: () -> Unit) {
    val colors = RommTheme.colors
    val context = LocalContext.current
    val shape = MaterialTheme.shapes.small
    val downloaded = p?.downloaded ?: e.downloadedBytes
    val total = if ((p?.total ?: 0) > 0) p!!.total else e.totalBytes
    val percent = Format.percent(downloaded, total)
    val state = p?.state ?: e.state
    val line = when (state) {
        DownloadState.DOWNLOADING -> buildString {
            append("$percent% · ${Format.bytes(downloaded)}")
            if (total > 0) append(" / ${Format.bytes(total)}")
            if (p != null && p.bytesPerSecond > 0) append(" · ${Format.speed(p.bytesPerSecond)} · ${Format.eta(p.etaSeconds)}")
        }
        DownloadState.PENDING -> stringResource(R.string.state_pending) + if (e.error != null) " · " + downloadErrorText(context, e.error) else ""
        DownloadState.PAUSED -> stringResource(R.string.state_paused) + " · $percent%"
        DownloadState.VERIFYING -> stringResource(R.string.state_verifying)
        DownloadState.EXTRACTING -> stringResource(R.string.state_extracting) + " · $percent%"
        DownloadState.MOVING -> stringResource(R.string.state_moving)
        DownloadState.COMPLETED -> (e.resultPath ?: e.targetRelDir) + if (total > 0) " · ${Format.bytes(total)}" else ""
        DownloadState.FAILED -> downloadErrorText(context, e.error)
        DownloadState.CANCELLED -> stringResource(R.string.state_cancelled)
        DownloadState.SKIPPED -> stringResource(R.string.state_present)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onFocusChanged { if (it.hasFocus) onFocus() }
            .gamepadFocusRow(shape)
            .clip(shape)
            .combinedClickable(onClick = onPrimary, onLongClick = onMenu)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(e.coverSmallPath, e.title, Modifier.width(34.dp).height(46.dp), shape = MaterialTheme.shapes.small)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.platformName, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, maxLines = 1)
                Text(line, style = MaterialTheme.typography.bodySmall, color = if (state == DownloadState.FAILED) colors.red else colors.onSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            val icon = when (state) {
                DownloadState.DOWNLOADING, DownloadState.PENDING -> Icons.Rounded.Pause
                DownloadState.PAUSED -> Icons.Rounded.PlayArrow
                DownloadState.FAILED, DownloadState.CANCELLED -> Icons.Rounded.Refresh
                DownloadState.COMPLETED, DownloadState.SKIPPED -> Icons.Rounded.Check
                else -> null
            }
            when {
                icon == Icons.Rounded.Check -> Icon(icon, null, tint = colors.green, modifier = Modifier.size(22.dp))
                icon != null -> IconButton(onClick = onPrimary, modifier = Modifier.size(32.dp)) { Icon(icon, null, tint = colors.primaryLighten) }
                else -> Box(Modifier.size(32.dp))
            }
            if (!e.state.isTerminal) {
                IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Cancel, stringResource(R.string.action_more), tint = colors.onSurfaceMuted) }
            } else {
                IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_more), tint = colors.onSurfaceMuted) }
            }
        }
        if (state == DownloadState.DOWNLOADING || state == DownloadState.PAUSED || state == DownloadState.EXTRACTING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = if (state == DownloadState.PAUSED) colors.gray else colors.primary, trackColor = colors.toplayer, drawStopIndicator = {})
        }
        if (state == DownloadState.FAILED) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.dl_attempts, e.attempt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted)
            }
        }
    }
}
