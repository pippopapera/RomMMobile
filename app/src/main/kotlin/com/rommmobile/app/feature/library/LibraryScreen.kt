package com.rommmobile.app.feature.library

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.rommmobile.app.core.design.PillShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.rommmobile.app.core.design.gamepadTextField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import com.rommmobile.app.core.input.ModalScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.ErrorState
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.OfflineBanner
import com.rommmobile.app.core.design.PadBar
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusIcon
import com.rommmobile.app.core.input.GamepadAction
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import com.rommmobile.app.core.input.GridBringIntoViewSpec
import com.rommmobile.app.core.input.gridDpadNavigation
import com.rommmobile.app.core.input.gridFocusItem
import com.rommmobile.app.core.input.rememberGridFocusCoordinator
import com.rommmobile.app.core.input.rememberHasGamepad
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ApiErrorKind
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.data.prefs.ViewMode
import com.rommmobile.app.data.repo.LibrarySource
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.feature.downloads.DownloadMiniBar
import com.rommmobile.app.feature.downloads.FolderDialog
import com.rommmobile.app.feature.downloads.RedownloadDialog
import com.rommmobile.app.feature.main.key
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun libraryViewModel(source: LibrarySource, onlyPresent: Boolean = false): LibraryViewModel =
    hiltViewModel<LibraryViewModel, LibraryViewModel.Factory>(key = source.key + if (onlyPresent) ":present" else "") { factory ->
        factory.create(source, onlyPresent)
    }

/** Full-screen library for a platform or a collection. */
@Composable
fun LibraryScreen(
    source: LibrarySource,
    title: String,
    onBack: () -> Unit,
    onOpenGame: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onlyPresent: Boolean = false,
) {
    // The filter travels in the ViewModel key: flipping it after the first composition built
    // the unfiltered pager first and threw it away, one full first page per opening.
    val vm = libraryViewModel(source, onlyPresent)
    val state by vm.state.collectAsStateWithLifecycle()
    var showSort by remember { mutableStateOf(false) }
    // Search scoped to this folder. Open/closed lives here so the top bar icon and the pad's
    // Select drive the same field; the text itself belongs to the content below.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val meta by vm.meta.collectAsStateWithLifecycle()
    // On the Installed shelf the server total read "1043 giochi" above a hundred cards: the
    // number shown there is the one on the shelf card, what is actually on this device.
    val presence by vm.presence.collectAsStateWithLifecycle()
    val shownTotal = if (onlyPresent && source is LibrarySource.ByPlatform) presence[source.platformSlug]?.size else meta.total
    val subtitle = shownTotal?.let { stringResource(R.string.n_games, it) }

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = title, subtitle = subtitle, onBack = onBack) {
            // Not on the Installed shelf: its list is the server's pages sieved through the local
            // index, and a term that leaves the first pages empty would stall Paging with a false
            // "nothing found". That shelf is short by nature; the folders are where search earns
            // its place.
            if (!onlyPresent) {
                IconButton(onClick = { searchOpen = !searchOpen }, modifier = Modifier.gamepadFocusIcon()) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.hint_search))
                }
            }
            IconButton(onClick = { vm.toggleView() }, modifier = Modifier.gamepadFocusIcon()) {
                Icon(if (state.viewMode == ViewMode.GRID) Icons.Rounded.ViewList else Icons.Rounded.GridView, contentDescription = stringResource(R.string.action_toggle_view))
            }
            IconButton(onClick = { showSort = true }, modifier = Modifier.gamepadFocusIcon()) {
                Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.sort_title))
            }
        }
        LibraryContent(
            vm = vm,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            showPlatform = source !is LibrarySource.ByPlatform,
            onOpenGame = onOpenGame,
            onOpenDownloads = onOpenDownloads,
            showSort = showSort,
            onShowSort = { showSort = it },
            onLeaveAfterDelete = onBack,
            searchPlaceholder = if (onlyPresent) null else stringResource(R.string.search_in_placeholder, title),
            searchOpen = searchOpen,
            onSearchOpen = { searchOpen = it },
        )
        // The queue bar must follow the user everywhere downloads can be started.
        DownloadMiniBar(onClick = onOpenDownloads)
    }
}

/**
 * The grid / list itself, reusable by Search and the two-pane layout. Owns the alphabet rail,
 * the letter jumps, the focused item and the gamepad shortcuts of the list.
 */
@Composable
fun LibraryContent(
    vm: LibraryViewModel,
    modifier: Modifier = Modifier,
    showPlatform: Boolean,
    onOpenGame: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    showSort: Boolean = false,
    onShowSort: (Boolean) -> Unit = {},
    emptyMessage: String? = null,
    /** False when an enclosing screen already owns the R3 shortcut (Home shell, two-pane). */
    handleDownloadsShortcut: Boolean = true,
    /** Where a finished bulk delete leaves the user: back on the platform they came from. */
    onLeaveAfterDelete: () -> Unit = {},
    /**
     * Search inside this list, scoped to its source. Null for a list that has its own field (the
     * Search tab) - the placeholder names the folder, "Search in Game Boy".
     */
    searchPlaceholder: String? = null,
    searchOpen: Boolean = false,
    onSearchOpen: (Boolean) -> Unit = {},
) {
    val layout = RommTheme.layout
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val presence by vm.presence.collectAsStateWithLifecycle()
    val progress by vm.progressByRom.collectAsStateWithLifecycle()
    val pending by vm.enqueue.pending.collectAsStateWithLifecycle()
    val redownload by vm.enqueue.confirmRedownload.collectAsStateWithLifecycle()
    val items: LazyPagingItems<Rom> = vm.items.collectAsLazyPagingItems()
    val hasGamepad by rememberHasGamepad()
    val searchable = searchPlaceholder != null
    // The term outlives this composition in the ViewModel (a game opened from the results and
    // closed again finds the same list); the field text is only its editable mirror.
    var text by rememberSaveable { mutableStateOf(state.searchTerm) }
    // What the pager shows versus what the field says. The ViewModel sets appliedTerm only once
    // a query's first page has landed, so "typed == applied" means the list on screen is the one
    // the user asked for, and anything that wants to move the cursor can trust its indices.
    fun effective(s: String) = s.trim().let { if (it.length >= 2) it else "" }
    val appliedTerm = state.appliedTerm
    val resultsShown = searchable && appliedTerm.isNotEmpty()
    val fieldFr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var fieldFocused by remember { mutableStateOf(false) }
    // The pad reaches the field through Select only. Left reachable by directional search, Up
    // from the first row of results (or Down from the top bar with no results) climbed into it
    // and threw the keyboard back up. Touch is exempt: a tap on the field must still work.
    var fieldWanted by remember { mutableStateOf(false) }
    val touchMode = LocalInputModeManager.current.inputMode == InputMode.Touch
    // The card the pad was on before the search opened; closing the search returns to it.
    var preSearchFocus by rememberSaveable { mutableIntStateOf(0) }
    // The pad's next stop in the list, owed until the list it refers to is on screen and the row
    // exists. Null when nothing is owed.
    var pendingFocus by remember { mutableStateOf<Int?>(null) }
    var focusJob by remember { mutableStateOf<Job?>(null) }
    // A single request can fail while the field is still being placed; a few spaced ones cannot.
    // Stops at the first success, or a late retry dragged focus back after the user had moved on.
    fun focusField() {
        fieldWanted = true
        pendingFocus = null
        focusJob?.cancel()
        focusJob = scope.launch {
            repeat(6) {
                runCatching { fieldFr.requestFocus() }
                // Focus alone is meant to raise the keyboard; asking explicitly covers the
                // devices where it does not, and is a no-op where it already did.
                if (fieldFocused) { keyboard?.show(); return@launch }
                delay(40)
            }
        }
    }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    // Paging takes its load hints from the VIEWPORT, not from every composed cell. The focused
    // card is pinned so it stays composed while the grid scrolls away under it; a pinned cell that
    // keeps asking for its own index reads to Paging as the user being THERE, far from the rows on
    // screen, which it answers by refreshing around it - the viewport turns to placeholders, asks
    // back, and the two ends refetch each other until the screen is left. So cells peek, and only
    // the first and last visible index ask; prefetch distance does the rest.
    val gridMode = state.viewMode == ViewMode.GRID
    LaunchedEffect(items, gridMode) {
        snapshotFlow {
            val info = if (gridMode) gridState.layoutInfo.visibleItemsInfo.let { it.firstOrNull()?.index to it.lastOrNull()?.index }
            else listState.layoutInfo.visibleItemsInfo.let { it.firstOrNull()?.index to it.lastOrNull()?.index }
            Triple(info.first, info.second, items.itemCount)
        }.distinctUntilChanged().collect { (first, last, count) ->
            if (count == 0 || first == null || last == null) return@collect
            items[first.coerceIn(0, count - 1)]
            items[last.coerceIn(0, count - 1)]
        }
    }
    val gridFocus = rememberGridFocusCoordinator(gridState)
    val isGrid = state.viewMode == ViewMode.GRID
    val installed = state.presenceFilter == PresenceFilter.PRESENT
    val letters = remember(meta) { vm.letters(meta) }
    // Keyed on isGrid: without a key the lambda would capture the view mode of the first
    // composition and keep reading the other list's scroll position after a Y toggle.
    val firstVisible by remember(isGrid) { derivedStateOf { if (isGrid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex } }
    var gridColumns by remember { mutableIntStateOf(1) }
    // Where the SELECTION is when it is on screen, otherwise the middle of the first visible row.
    // Two reasons: with several columns the top row is usually shared between two letters, and at
    // the end of the list the grid cannot scroll further, so the top row lags a letter behind.
    val anchorIndex by remember(isGrid, gridColumns) {
        derivedStateOf {
            if (!isGrid) listState.firstVisibleItemIndex
            else {
                val info = gridState.layoutInfo.visibleItemsInfo
                val first = info.firstOrNull()?.index ?: 0
                val last = info.lastOrNull()?.index ?: first
                if (gridFocus.cursorValid && gridFocus.cursor in first..last) gridFocus.cursor
                else first + gridColumns / 2
            }
        }
    }
    val currentLetter = remember(letters, anchorIndex) { vm.currentLetter(letters, anchorIndex)?.label }
    // No alphabet over results: the rail indexes the whole folder, not a result set. Keyed on the
    // applied term, so the grid reflows once when results land and once when they go, never per
    // keystroke, and stays put while the field is merely open.
    val railVisible = state.railAvailable && letters.any { it.offset != null } && items.itemCount > 0 && !resultsShown

    var focusedRom by remember { mutableStateOf<Rom?>(null) }
    // Survives leaving for a game and coming back, so B lands on the game you opened rather than
    // on the first card of the page.
    var lastFocused by rememberSaveable { mutableIntStateOf(0) }

    // Falling back to the top visible item keeps the shortcuts alive when focus sits on the top bar
    // rather than on a card.
    fun target(): Rom? = focusedRom ?: items.itemSnapshotList.getOrNull(firstVisible)
    // Bulk uninstall lives only on the Installed shelf: everywhere else there is nothing to remove.
    var selecting by remember { mutableStateOf(false) }
    val picked = remember { mutableStateMapOf<Int, Rom>() }
    var confirmBulk by remember { mutableStateOf(false) }
    fun leaveSelection() { selecting = false; picked.clear() }
    // The two never contend today (search is off on the Installed shelf, the only place a
    // selection exists); the order still says which mode is the inner one should that change.
    BackHandler(enabled = searchOpen) { onSearchOpen(false) }
    BackHandler(enabled = selecting) { leaveSelection() }
    var pendingFocusIndex by remember { mutableIntStateOf(-1) }
    var jumpLabel by remember { mutableStateOf<String?>(null) }
    val containerFr = remember { FocusRequester() }

    // Letter jumps: scroll, then hand focus to the first item of that letter.
    LaunchedEffect(vm, isGrid) {
        vm.jumps.collect { offset ->
            val target = offset.coerceIn(0, (items.itemCount - 1).coerceAtLeast(0))
            if (isGrid) {
                gridState.scrollToItem(target)
                // pendingFocusIndex is only read by the list branch, so the grid needs its own
                // path or the pad cursor stays where it was and X downloads the wrong game.
                if (hasGamepad) gridFocus.focusAt(target) else gridFocus.moveCursor(target)
            } else {
                listState.scrollToItem(target)
                pendingFocusIndex = target
            }
            jumpLabel = vm.currentLetter(letters, target)?.label
        }
    }
    LaunchedEffect(jumpLabel) { if (jumpLabel != null) { delay(600); jumpLabel = null } }

    // Give the first card focus once the first page is in (gamepad users only). requestFocus can
    // legitimately fail while the list is still being laid out, so keep trying for a moment:
    // without focus the X shortcut has nothing to download and the screen feels dead.
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(items.itemCount, hasGamepad, isGrid) {
        if (initialFocusDone || !hasGamepad || items.itemCount == 0) return@LaunchedEffect
        initialFocusDone = true
        delay(80)
        val target = lastFocused.coerceIn(0, items.itemCount - 1)
        // The grid has its own coordinator, which retries across frames; the list goes through
        // pendingFocusIndex, which only the LazyColumn branch consumes. Sending the grid down the
        // list path left it with nothing focused at all.
        if (isGrid) {
            gridFocus.focusAt(target)
        } else {
            listState.scrollToItem(target)
            pendingFocusIndex = target
        }
    }

    // Where the list goes next: the pad's cursor, or just the scroll position for fingers. Applied
    // at once when the typed term is already the one the pager shows; otherwise it waits for that
    // pager's first page, and then for the row itself, which deep in a long folder may still be a
    // placeholder. Never while the field has focus.
    fun requestListFocus(index: Int) { pendingFocus = index }
    val refresh = items.loadState.refresh
    LaunchedEffect(pendingFocus, items.loadState, items.itemCount, appliedTerm, state.searchTerm, fieldFocused) {
        val target = pendingFocus ?: return@LaunchedEffect
        if (fieldFocused) return@LaunchedEffect
        if (effective(state.searchTerm) != appliedTerm) return@LaunchedEffect
        if (refresh !is LoadState.NotLoading) return@LaunchedEffect
        if (items.itemCount == 0) { pendingFocus = null; return@LaunchedEffect }
        val idx = target.coerceIn(0, items.itemCount - 1)
        if (items.peek(idx) == null) {
            // Bring the placeholder into view so Paging fetches its page; the load state change
            // runs this again with the row in place.
            if (isGrid) gridState.scrollToItem(idx) else listState.scrollToItem(idx)
            return@LaunchedEffect
        }
        pendingFocus = null
        lastFocused = idx
        if (isGrid) {
            // The coordinator learns the new count from an effect inside the grid, which runs
            // after this one; left stale at the result count it would clamp the index to 0.
            gridFocus.itemCount = items.itemCount
            if (hasGamepad) gridFocus.focusAt(idx) else gridState.scrollToItem(idx)
        } else {
            listState.scrollToItem(idx)
            if (hasGamepad) pendingFocusIndex = idx
        }
    }
    // The keyboard was put away on purpose (B, or the keyboard's own search key): the pad lands
    // on the first result, or back where it was when nothing was typed. Up or Down out of the
    // field move focus themselves and never come through here.
    fun handOff() { requestListFocus(if (effective(vm.state.value.searchTerm).isEmpty()) preSearchFocus else 0) }

    // Opening focuses the field (and with it the keyboard); closing wipes the term so the folder
    // is whole again, and sends the cursor back to the card it left. Compared against the value
    // seen at first composition so a restored "open" (rotation, process death, back from a game)
    // does not throw the keyboard up on its own.
    var wasOpen by remember { mutableStateOf(searchOpen) }
    LaunchedEffect(searchOpen) {
        if (!searchable) return@LaunchedEffect
        if (searchOpen && !wasOpen) {
            // The card under the cursor when it is on screen; otherwise the top of the viewport,
            // because a finger (or a letter jump made by touch) moved the list without moving
            // the cursor, and closing must come back to what was being looked at.
            val visible = if (isGrid) gridState.layoutInfo.visibleItemsInfo.map { it.index } else listState.layoutInfo.visibleItemsInfo.map { it.index }
            preSearchFocus = if (lastFocused in visible) lastFocused else (visible.firstOrNull() ?: lastFocused)
            focusField()
        } else if (!searchOpen && wasOpen) {
            if (text.isNotEmpty()) { text = ""; vm.setSearchTerm("") }
            keyboard?.hide()
            requestListFocus(preSearchFocus)
        }
        wasOpen = searchOpen
    }
    // Saved state and ViewModel can disagree after process death or a pane switch: the text
    // survives and the term does not, or the other way round. The field is the truth while it is
    // shown, and no term may run without a field that shows it.
    LaunchedEffect(Unit) {
        if (!searchable) return@LaunchedEffect
        if (searchOpen) { if (text != state.searchTerm) vm.setSearchTerm(text) }
        else if (state.searchTerm.isNotEmpty()) { text = ""; vm.setSearchTerm("") }
    }
    // Every result set opens at its top. Keyed on the applied term: once per page that lands,
    // never per keystroke, and no viewport hint is sent to a pager on its way out.
    LaunchedEffect(appliedTerm) {
        if (resultsShown && pendingFocus == null) { if (isGrid) gridState.scrollToItem(0) else listState.scrollToItem(0) }
    }

    // While the field has the keys, no shortcut may fire anywhere: the keyboard does not eat face
    // or shoulder buttons, so Y, Start, L1/R1 would still reach their screens behind it.
    if (fieldFocused) ModalScope()

    // First page failed for connectivity reasons: fall back to the local cache when possible.
    LaunchedEffect(refresh) {
        val err = (refresh as? LoadState.Error)?.error?.let { ApiException.from(it) } ?: return@LaunchedEffect
        if (err.kind == ApiErrorKind.OFFLINE || err.kind == ApiErrorKind.TIMEOUT) vm.tryOffline()
    }

    Column(modifier) {
        // The library's own offline mode swaps the pager to the Room cache, so it needs a way out
        // even more than the Platforms banner does.
        OfflineBanner(visible = state.offline, onRetry = { vm.retryOnline(); items.refresh() })
        if (searchable && searchOpen) {
            FolderSearchField(
                value = text,
                onValueChange = { text = it; vm.setSearchTerm(it) },
                placeholder = searchPlaceholder!!,
                // The keyboard's own search key: put it away, let go of the field, and hand the
                // pad the results exactly as B does.
                onSearch = { keyboard?.hide(); focusManager.clearFocus(); handOff() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = layout.padding, vertical = 4.dp)
                    .focusRequester(fieldFr)
                    .focusProperties { canFocus = fieldWanted || touchMode }
                    .onFocusChanged { f ->
                        if (f.isFocused) fieldFocused = true
                        else if (fieldFocused) { fieldFocused = false; fieldWanted = false }
                    }
                    .gamepadTextField(MaterialTheme.shapes.medium, onDismissed = { handOff() }),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                refresh is LoadState.Error && items.itemCount == 0 -> {
                    val err = ApiException.from(refresh.error)
                    ErrorState(message = err.userMessage(context), onRetry = { vm.retryOnline(); items.retry() })
                }
                refresh is LoadState.NotLoading && items.itemCount == 0 -> {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = if (resultsShown) stringResource(R.string.search_no_results, appliedTerm) else emptyMessage ?: stringResource(R.string.library_empty),
                        // Offline the search only sees the pages ever cached: say so rather than
                        // claim the game does not exist.
                        subtitle = if (resultsShown && state.offline) stringResource(R.string.search_no_results_offline) else null,
                    )
                }
                else -> Row(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxSize()) {
                        val columns = remember(maxWidth, layout) {
                            (maxWidth / layout.coverMinCell).toInt().coerceIn(2, layout.maxColumns)
                        }
                        // Cells wider than a thumbnail get the full-resolution cover.
                        val largeCovers = remember(maxWidth, columns) { maxWidth / columns >= 110.dp }
                        LaunchedEffect(columns, items.itemCount) {
                            gridColumns = columns
                            gridFocus.columns = columns
                            gridFocus.itemCount = items.itemCount
                        }
                        if (isGrid) {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides GridBringIntoViewSpec) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = gridState,
                                contentPadding = PaddingValues(layout.padding),
                                horizontalArrangement = Arrangement.spacedBy(layout.gap),
                                verticalArrangement = Arrangement.spacedBy(layout.gap),
                                modifier = Modifier.fillMaxSize().focusRequester(containerFr).focusGroup()
                                    .gridDpadNavigation(gridFocus).rightStickScroll(gridState),
                            ) {
                                items(count = items.itemCount, key = items.itemKey { it.id }, contentType = { "rom" }) { index ->
                                    val rom = items.peek(index)
                                    if (rom == null) {
                                        GameCardSkeleton()
                                    } else {
                                        GameCard(
                                            rom = rom,
                                            status = statusOf(rom, presence, progress),
                                            showPlatform = showPlatform,
                                            platformIconUrl = if (showPlatform) vm.platformIconUrl(rom.platformSlug) else null,
                                            modifier = Modifier.gridFocusItem(index, gridFocus),
                                            largeCover = largeCovers,
                                            onFocused = { focusedRom = it; lastFocused = index },
                                            selecting = selecting,
                                            selected = picked.containsKey(rom.id),
                                            onClick = {
                                                if (selecting) {
                                                    if (picked.remove(rom.id) == null) picked[rom.id] = rom
                                                } else onOpenGame(rom.id)
                                            },
                                            onLongClick = { if (installed) selecting = true },
                                        )
                                    }
                                }
                            }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = layout.padding / 2),
                                modifier = Modifier.fillMaxSize().focusRequester(containerFr).focusGroup().focusRestorer().rightStickScroll(listState),
                            ) {
                                items(count = items.itemCount, key = items.itemKey { it.id }, contentType = { "row" }) { index ->
                                    val rom = items.peek(index)
                                    if (rom == null) {
                                        GameRowSkeleton()
                                    } else {
                                        val fr = if (index == pendingFocusIndex) remember { FocusRequester() } else null
                                        GameRow(
                                            rom = rom,
                                            status = statusOf(rom, presence, progress),
                                            showPlatform = showPlatform,
                                            modifier = if (fr != null) Modifier.focusRequester(fr) else Modifier,
                                            selecting = selecting,
                                            selected = picked.containsKey(rom.id),
                                            onFocused = { focusedRom = it; lastFocused = index },
                                            onClick = {
                                                if (selecting) {
                                                    if (picked.remove(rom.id) == null) picked[rom.id] = rom
                                                } else onOpenGame(rom.id)
                                            },
                                            onLongClick = { if (installed) selecting = true },
                                        )
                                        if (fr != null) LaunchedEffect(index) { runCatching { fr.requestFocus() }; pendingFocusIndex = -1 }
                                    }
                                }
                            }
                        }
                        JumpLabel(jumpLabel, Modifier.align(Alignment.Center))
                    }
                    if (railVisible) {
                        AlphabetRail(letters = letters, current = currentLetter, onJump = { vm.jumpTo(it) })
                    }
                }
            }
        }
        PadBar(installed, isGrid, handleDownloadsShortcut, selecting, searchable, searchOpen, fieldFocused) {
            // Select searches this folder. Already open: it puts the cursor back in the field, so
            // refining a term is one press away from the results. B is the only way out.
            if (searchable && !selecting) {
                bind(GamepadAction.CONTEXT_MENU, R.string.hint_search) { if (searchOpen) focusField() else onSearchOpen(true) }
            }
            // Installed shelf: the file is already on the card, so X could only offer to
            // overwrite it. Uninstall and download-again live in the ≡ menu, which is the one
            // place either of them belongs.
            // X has no job in a normal library: a download starts in one place only, the game's own
            // page. On the Installed shelf X opens the multi-select, and once open it IS the delete.
            if (installed) {
                if (selecting) {
                    bind(GamepadAction.DOWNLOAD, R.string.action_delete) {
                        if (picked.isEmpty()) vm.nothingSelected() else confirmBulk = true
                    }
                } else {
                    bind(GamepadAction.DOWNLOAD, R.string.action_select) { selecting = true }
                }
            }
            // A mode is a smaller world: while selecting, the bar is X and nothing else. Flipping
            // the view or re-sorting mid-selection only gave the picks somewhere to hide. While
            // typing the bus is muted anyway (ModalScope above); dropping the rows keeps the bar
            // from advertising what would not fire.
            if (!selecting && !fieldFocused) {
                bind(
                    GamepadAction.TOGGLE_VIEW,
                    if (isGrid) R.string.hint_view_list else R.string.hint_view_grid,
                ) { vm.toggleView() }
                bind(GamepadAction.FILTERS, R.string.sort_title) { onShowSort(true) }
            }
            // The queue bar sits right below and shows where R3 goes.
            if (handleDownloadsShortcut) silent(GamepadAction.OPEN_DOWNLOADS) { onOpenDownloads() }
        }
    }

    if (showSort) {
        SortFilterDialog(state = state, onSort = vm::setSort, onDismiss = { onShowSort(false) })
    }
    pending?.let { p ->
        FolderDialog(pending = p, onCreate = vm::confirmCreateFolder, onChoose = vm::chooseFolder, onDismiss = vm.enqueue::dismiss)
    }
    redownload?.let { rom ->
        RedownloadDialog(rom = rom, onConfirm = { vm.confirmRedownload() }, onDismiss = vm.enqueue::dismissRedownload)
    }
    if (confirmBulk) {
        val count = picked.size
        RommDialog(
            onDismissRequest = { confirmBulk = false },
            title = stringResource(R.string.bulk_delete_title, count),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                confirmBulk = false
                val victims = picked.values.toList()
                leaveSelection()
                vm.uninstallAll(victims)
                onLeaveAfterDelete()
            },
            destructive = true,
        ) {
            Text(stringResource(R.string.bulk_delete_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun statusOf(rom: Rom, presence: Map<String, Set<String>>, progress: Map<Int, com.rommmobile.app.feature.downloads.engine.DownloadProgress>): ItemStatus {
    val present = presence[rom.platformSlug]?.contains(rom.nameKey) == true
    val p = progress[rom.id]
    return if (!present && p == null) ItemStatus.NONE else ItemStatus(present, p)
}

/**
 * The folder search box. Built on BasicTextField with Material's outlined decoration so it can
 * sit at 48 dp (the floor its icon slots impose) instead of the 56 dp an OutlinedTextField
 * insists on: on a short handheld screen those 8 dp are a visible slice of the one row of covers
 * left under the keyboard.
 */
@Composable
private fun FolderSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val shape = MaterialTheme.shapes.medium
    // The clear button is for fingers. On the pad, Up from the first result used to land on it
    // instead of leaving the field alone; the pad clears by deleting or closes with B.
    val touchMode = LocalInputModeManager.current.inputMode == InputMode.Touch
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
        cursorBrush = SolidColor(RommTheme.colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                placeholder = { Text(placeholder, maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { onValueChange("") }, modifier = Modifier.focusProperties { canFocus = touchMode }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_clear))
                        }
                    }
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(enabled = true, isError = false, interactionSource = interaction, colors = colors, shape = shape)
                },
            )
        },
    )
}
