package com.rommmobile.app.feature.downloads

import android.content.Context
import androidx.compose.runtime.Immutable
import com.rommmobile.app.R
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.mapping.Resolution
import com.rommmobile.app.data.repo.DownloadRepository
import com.rommmobile.app.data.repo.EnqueueResult
import com.rommmobile.app.data.repo.Platform
import com.rommmobile.app.data.repo.Rom
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** A download waiting for a one-time folder decision. */
@Immutable
data class PendingFolder(val rom: Rom, val platform: Platform, val resolution: Resolution)

/**
 * Shared "press download" behaviour for grids, lists and the detail screen: enqueue, toast
 * where the file went, or surface the single folder question when a platform is new.
 */
class EnqueueController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadRepository,
    private val mapping: MappingRepository,
    private val toaster: Toaster,
) {
    private val _pending = MutableStateFlow<PendingFolder?>(null)
    val pending: StateFlow<PendingFolder?> = _pending.asStateFlow()

    private val _confirmRedownload = MutableStateFlow<Rom?>(null)
    /** Set when the game is already on the device; the UI asks before downloading again. */
    val confirmRedownload: StateFlow<Rom?> = _confirmRedownload.asStateFlow()

    suspend fun enqueue(rom: Rom, force: Boolean = false) {
        when (val r = downloads.enqueueRom(rom, force)) {
            is EnqueueResult.Enqueued -> toaster.show(context.getString(R.string.toast_enqueued, r.relDir))
            EnqueueResult.AlreadyQueued -> toaster.show(context.getString(R.string.toast_already_queued))
            EnqueueResult.AlreadyPresent -> _confirmRedownload.value = rom
            is EnqueueResult.NeedsFolder -> _pending.value = PendingFolder(rom, r.platform, r.resolution)
            EnqueueResult.NoRoot -> toaster.show(context.getString(R.string.toast_no_root), isError = true)
            EnqueueResult.NotConfigured -> toaster.show(context.getString(R.string.error_not_configured), isError = true)
        }
    }

    suspend fun confirmRedownload() {
        val rom = _confirmRedownload.value ?: return
        _confirmRedownload.value = null
        enqueue(rom, force = true)
    }

    fun dismissRedownload() { _confirmRedownload.value = null }

    suspend fun confirmCreate(name: String) {
        val p = _pending.value ?: return
        val created = mapping.createFolder(p.platform.slug, name.trim().ifEmpty { p.platform.slug })
        _pending.value = null
        if (created == null) { toaster.show(context.getString(R.string.toast_folder_create_failed), isError = true); return }
        enqueue(p.rom)
    }

    suspend fun chooseCandidate(relDir: String) {
        val p = _pending.value ?: return
        mapping.chooseCandidate(p.platform.slug, relDir)
        _pending.value = null
        enqueue(p.rom)
    }

    fun dismiss() { _pending.value = null }
}
