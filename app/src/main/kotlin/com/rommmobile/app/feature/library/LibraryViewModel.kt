package com.rommmobile.app.feature.library

import android.content.Context
import com.rommmobile.app.R
import com.rommmobile.app.core.design.Toaster
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.filter
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.network.UrlNormalizer
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.prefs.SortField
import com.rommmobile.app.data.prefs.ViewMode
import com.rommmobile.app.data.repo.DownloadRepository
import com.rommmobile.app.data.repo.LibraryMeta
import com.rommmobile.app.data.repo.LibraryQuery
import com.rommmobile.app.data.repo.LibrarySource
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.data.repo.RomRepository
import com.rommmobile.app.feature.downloads.EnqueueController
import com.rommmobile.app.feature.downloads.engine.DownloadProgress
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.paging.cachedIn

/** PRESENT is set only by the On device section; there is no filter control for it. */
enum class PresenceFilter { ALL, PRESENT }

@Immutable
data class LetterEntry(val label: String, val offset: Int?)

@Immutable
data class LibraryUiState(
    val viewMode: ViewMode = ViewMode.GRID,
    val sort: SortField = SortField.NAME,
    val descending: Boolean = false,
    val presenceFilter: PresenceFilter = PresenceFilter.ALL,
    val regions: List<String> = emptyList(),
    val offline: Boolean = false,
    val searchTerm: String = "",
    /**
     * The term the list on screen was built for ("" when none), set only once that pager's first
     * page has landed. The screen compares it with [searchTerm] to know whether the results it is
     * looking at are the ones the user typed, or still on their way.
     */
    val appliedTerm: String = "",
) {
    /** The alphabet rail only makes sense for the server's name order without client filters. */
    val railAvailable: Boolean get() = sort == SortField.NAME && !descending && presenceFilter == PresenceFilter.ALL && !offline
}

@HiltViewModel(assistedFactory = LibraryViewModel.Factory::class)
class LibraryViewModel @AssistedInject constructor(
    @Assisted val source: LibrarySource,
    /** The Installed shelf. Known from the start so the first pager is already the filtered one. */
    @Assisted val onlyPresent: Boolean,
    private val roms: RomRepository,
    private val settings: SettingsStore,
    private val localLibrary: LocalLibraryRepository,
    private val downloads: DownloadRepository,
    private val serverStore: ServerStore,
    @ApplicationContext private val context: Context,
    private val toaster: Toaster,
    val enqueue: EnqueueController,
) : ViewModel() {

    @AssistedFactory
    interface Factory { fun create(source: LibrarySource, onlyPresent: Boolean): LibraryViewModel }

    private val _state = MutableStateFlow(LibraryUiState(presenceFilter = if (onlyPresent) PresenceFilter.PRESENT else PresenceFilter.ALL))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _meta = MutableStateFlow(LibraryMeta())
    val meta: StateFlow<LibraryMeta> = _meta.asStateFlow()

    private val _jumps = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** Absolute list index to scroll to (letter jumps). */
    val jumps: SharedFlow<Int> = _jumps.asSharedFlow()

    val baseUrl: StateFlow<String?> = serverStore.config.map { it.activeUrl }.stateIn(viewModelScope, SharingStarted.Eagerly, serverStore.config.value.activeUrl)

    /**
     * One platform's keys when the list is one platform, the whole map only for mixed lists.
     * Shared with replay so the pager below waits for the first real value instead of filtering
     * everything out against an empty map for a frame.
     */
    private val presenceFlow: Flow<Map<String, Set<String>>> = run {
        val src = source
        val raw = if (src is LibrarySource.ByPlatform) localLibrary.presenceKeys(src.platformSlug).map { keys -> mapOf(src.platformSlug to keys) }
        else localLibrary.presenceMap()
        raw.distinctUntilChanged().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }
    val presence: StateFlow<Map<String, Set<String>>> =
        presenceFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** romId -> live progress, sampled so the grid recomposes at most 4 times a second. */
    val progressByRom: StateFlow<Map<Int, DownloadProgress>> =
        combine(downloads.observeOpen(), downloads.progress) { open, prog ->
            open.associate { e -> e.romId to (prog[e.id] ?: DownloadProgress(e.id, e.downloadedBytes, e.totalBytes, 0, e.state)) }
        }.sample(250).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val searchTerm = MutableStateFlow("")
    private var metaGen = 0

    private val query: Flow<LibraryQuery> = combine(
        _state.map { Triple(it.sort, it.descending, it.regions) }.distinctUntilChanged(),
        searchTerm.debounce { if (it.isEmpty()) 0L else 450L }.map { it.trim() }.distinctUntilChanged(),
    ) { (sort, desc, regions), term ->
        LibraryQuery(source, term.takeIf { it.length >= 2 }, sort, desc, regions)
    }.distinctUntilChanged()

    private val presenceFilter: Flow<PresenceFilter> = _state.map { it.presenceFilter }.distinctUntilChanged()

    /** Rebuilt only when the query, the filter mode or the offline flag change. */
    private val basePager: Flow<PagingData<Rom>> = combine(
        query,
        presenceFilter,
        _state.map { it.offline }.distinctUntilChanged(),
    ) { q, filter, offline ->
        Triple(q, filter, offline)
    }.flatMapLatest { (q, filter, offline) ->
        if (source == LibrarySource.Search && q.searchTerm == null) return@flatMapLatest flowOf(PagingData.empty())
        // Cancelling the previous pager does not stop a response already in flight from calling
        // back: without the generation check a slow full-folder page landed after a search had
        // reset meta and put "1500 games" over three results.
        val gen = ++metaGen
        _meta.value = LibraryMeta()
        // filter() cannot remove placeholder slots, so a pager that will be filtered must not make
        // any: they would survive as skeleton cards that never resolve, and keep the unfiltered
        // item count that feeds focus and the letter jumps.
        val placeholders = filter == PresenceFilter.ALL
        val applied = q.searchTerm.orEmpty()
        if (offline && source is LibrarySource.ByPlatform) {
            // Room answers within the frame: no first-page moment worth waiting for.
            _state.update { it.copy(appliedTerm = applied) }
            roms.offlinePager(source.platformId, placeholders, q.searchTerm)
        } else {
            roms.pager(q, placeholders = placeholders) { m ->
                if (gen == metaGen) {
                    _meta.value = m
                    _state.update { it.copy(appliedTerm = applied) }
                }
            }
        }
    }.cachedIn(viewModelScope)

    /**
     * Presence is applied AFTER cachedIn, deliberately. Combining anything with a Flow<PagingData>
     * *before* it hands the same one-shot instance to a second collector and Paging throws
     * "Attempt to collect twice from pageEventFlow"; after it the stream is multicast, so a
     * finished download re-filters the list in place instead of rebuilding the pager and refetching.
     */
    val items: Flow<PagingData<Rom>> = combine(basePager, presenceFilter, presenceFlow) { data, filter, pres ->
        if (filter == PresenceFilter.ALL) data
        else data.filter { rom -> pres[rom.platformSlug]?.contains(rom.nameKey) == true }
    }

    init {
        viewModelScope.launch {
            val s = settings.snapshot()
            _state.update { it.copy(viewMode = s.viewMode, sort = s.sortField, descending = s.sortDescending) }
            settings.viewMode.collect { vm -> _state.update { it.copy(viewMode = vm) } }
        }
    }

    fun setSearchTerm(term: String) {
        searchTerm.value = term
        _state.update { it.copy(searchTerm = term) }
    }

    fun toggleView() {
        val next = if (_state.value.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
        _state.update { it.copy(viewMode = next) }
        viewModelScope.launch { settings.setViewMode(next) }
    }

    fun setSort(field: SortField, descending: Boolean) {
        _state.update { it.copy(sort = field, descending = descending) }
        viewModelScope.launch { settings.setSort(field, descending) }
    }

    fun setPresenceFilter(f: PresenceFilter) = _state.update { it.copy(presenceFilter = f) }

    /** Called by the UI when the first page fails with a connectivity error. */
    fun tryOffline(): Boolean {
        val src = source as? LibrarySource.ByPlatform ?: return false
        if (_state.value.offline) return true
        viewModelScope.launch {
            if (roms.cachedCount(src.platformId) > 0) _state.update { it.copy(offline = true) }
        }
        return true
    }

    fun retryOnline() = _state.update { it.copy(offline = false) }

    fun download(rom: Rom) = viewModelScope.launch { enqueue.enqueue(rom) }

    fun nothingSelected() = toaster.show(context.getString(R.string.select_none))

    /** Removes several games from this device in one go; the copies on RomM are untouched. */
    fun uninstallAll(roms: List<Rom>) = viewModelScope.launch {
        // The screen pops as soon as the user confirms, and popping clears this ViewModel, which
        // cancels its scope. Without NonCancellable the loop died after the first file on a slow
        // card and the user was told "2 removed" for a delete that had only half happened.
        withContext(NonCancellable) {
            var gone = 0
            for (rom in roms) gone += localLibrary.deleteGame(rom.platformSlug, rom.nameKey)
            toaster.show(
                if (gone > 0) context.getString(R.string.bulk_delete_done, gone) else context.getString(R.string.uninstall_failed),
                isError = gone == 0,
            )
        }
    }

    fun confirmCreateFolder(name: String) = viewModelScope.launch { enqueue.confirmCreate(name) }
    fun chooseFolder(relDir: String) = viewModelScope.launch { enqueue.chooseCandidate(relDir) }
    fun confirmRedownload() = viewModelScope.launch { enqueue.confirmRedownload() }

    fun platformIconUrl(slug: String): String? = baseUrl.value?.let { UrlNormalizer.join(it, "assets/platforms/$slug.ico") }

    /* ---------------- alphabet ---------------- */

    fun letters(meta: LibraryMeta): List<LetterEntry> {
        val offsets = HashMap<String, Int>()
        for ((k, v) in meta.charIndex) {
            val label = labelFor(k)
            val cur = offsets[label]
            if (cur == null || v < cur) offsets[label] = v
        }
        val out = ArrayList<LetterEntry>(28)
        out += LetterEntry("#", offsets["#"])
        for (c in 'A'..'Z') out += LetterEntry(c.toString(), offsets[c.toString()])
        offsets["@"]?.let { out += LetterEntry("@", it) }
        return out
    }

    private fun labelFor(key: String): String {
        val c = key.trim().uppercase().firstOrNull() ?: return "@"
        return when {
            c in 'A'..'Z' -> c.toString()
            c.isDigit() || c == '#' -> "#"
            else -> "@"
        }
    }

    /**
     * [anchor] is not the first visible index but the middle of the first visible row: a grid jump
     * puts the target's ROW on top, so that row still starts with the tail of the previous letter
     * and the first index would report the letter the user just left.
     */
    fun currentLetter(letters: List<LetterEntry>, anchor: Int): LetterEntry? =
        letters.filter { it.offset != null && it.offset <= anchor }.maxByOrNull { it.offset!! }

    fun jumpTo(entry: LetterEntry) { entry.offset?.let { _jumps.tryEmit(it) } }

}
