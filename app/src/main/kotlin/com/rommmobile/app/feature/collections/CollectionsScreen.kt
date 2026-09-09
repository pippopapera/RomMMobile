package com.rommmobile.app.feature.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.rommmobile.app.core.input.GridBringIntoViewSpec
import com.rommmobile.app.core.input.gridDpadNavigation
import com.rommmobile.app.core.input.gridFocusItem
import com.rommmobile.app.core.input.rememberGridFocusCoordinator
import com.rommmobile.app.core.input.rememberHasGamepad
import kotlinx.coroutines.delay
import com.rommmobile.app.R
import com.rommmobile.app.core.design.CoverImage
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.ErrorState
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.Skeleton
import com.rommmobile.app.core.design.StatusPill
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.data.db.CollectionKind
import com.rommmobile.app.data.repo.Collection
import com.rommmobile.app.data.repo.CollectionRepository
import com.rommmobile.app.feature.main.LibraryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CollectionTab(val labelRes: Int, val kind: CollectionKind, val virtualType: String?) {
    USER(R.string.coll_tab_user, CollectionKind.USER, null),
    SMART(R.string.coll_tab_smart, CollectionKind.SMART, null),
    FRANCHISE(R.string.coll_tab_franchise, CollectionKind.VIRTUAL, "franchise"),
    GENRE(R.string.coll_tab_genre, CollectionKind.VIRTUAL, "genre"),
    COMPANY(R.string.coll_tab_company, CollectionKind.VIRTUAL, "company"),
    MODE(R.string.coll_tab_mode, CollectionKind.VIRTUAL, "mode"),
    COLLECTION(R.string.coll_tab_collection, CollectionKind.VIRTUAL, "collection"),
}

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repo: CollectionRepository,
    serverStore: ServerStore,
) : ViewModel() {
    private val _tab = MutableStateFlow(CollectionTab.USER)
    val tab: StateFlow<CollectionTab> = _tab.asStateFlow()
    val baseUrl: StateFlow<String?> = serverStore.config.map { it.activeUrl }.stateIn(viewModelScope, SharingStarted.Eagerly, serverStore.config.value.activeUrl)

    private val _error = MutableStateFlow<ApiException?>(null)
    val error: StateFlow<ApiException?> = _error.asStateFlow()
    private val _loaded = MutableStateFlow(setOf<CollectionKind>())

    val items: StateFlow<List<Collection>?> = combine(_tab.flatMapLatest { t -> repo.observe(t.kind).map { l -> t to l } }, _loaded) { (t, list), loaded ->
        val filtered = if (t.virtualType != null) list.filter { it.virtualType == t.virtualType } else list
        if (filtered.isEmpty() && t.kind !in loaded) null else filtered
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { refresh(CollectionTab.USER) }

    fun select(t: CollectionTab) { _tab.value = t; refresh(t) }

    fun refresh(t: CollectionTab = _tab.value) = viewModelScope.launch {
        val r = when (t.kind) {
            CollectionKind.USER -> repo.refreshUser()
            CollectionKind.SMART -> repo.refreshSmart()
            CollectionKind.VIRTUAL -> repo.refreshVirtual()
        }
        r.onSuccess { _error.value = null }.onFailure { _error.value = ApiException.from(it) }
        _loaded.value = _loaded.value + t.kind
    }
}

@Composable
fun CollectionsScreen(onOpenLibrary: (LibraryRoute) -> Unit) {
    val vm: CollectionsViewModel = hiltViewModel()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val base by vm.baseUrl.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val layout = RommTheme.layout
    val colors = RommTheme.colors

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.section_collections))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup().padding(horizontal = layout.padding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CollectionTab.entries.forEach { t ->
                val selected = t == tab
                Text(
                    stringResource(t.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.white else colors.onSurface,
                    modifier = Modifier
                        .gamepadFocusRing(PillShape)
                        .clip(PillShape)
                        .background(if (selected) colors.primary else colors.surface)
                        .clickable { vm.select(t) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        val list = items
        when {
            list == null && error != null -> ErrorState(error!!.userMessage(context), onRetry = { vm.refresh() })
            list == null -> GridSkeleton()
            list.isEmpty() -> EmptyState(Icons.Rounded.Collections, stringResource(R.string.coll_empty))
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = (maxWidth / (layout.coverMinCell + 24.dp)).toInt().coerceIn(2, layout.maxColumns)
                val state = rememberLazyGridState()
                val gridFocus = rememberGridFocusCoordinator(state)
                val hasGamepad by rememberHasGamepad()
                LaunchedEffect(columns, list.size) {
                    gridFocus.columns = columns
                    gridFocus.itemCount = list.size
                }
                // Same contract as the Platforms and Installed grids: focusRestorer cannot survive
                // a navigation pop, so the cursor is kept here and put back by hand.
                var lastFocused by rememberSaveable { mutableIntStateOf(0) }
                var restored by remember { mutableStateOf(false) }
                LaunchedEffect(list.size) {
                    if (list.isEmpty()) return@LaunchedEffect
                    val target = lastFocused.coerceIn(0, list.lastIndex)
                    delay(80)
                    if (hasGamepad) gridFocus.focusAt(target) else if (target > 0) state.scrollToItem(target)
                    restored = true
                }
                LaunchedEffect(gridFocus.cursor) { if (restored) lastFocused = gridFocus.cursor }
                CompositionLocalProvider(LocalBringIntoViewSpec provides GridBringIntoViewSpec) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = state,
                        contentPadding = PaddingValues(layout.padding),
                        horizontalArrangement = Arrangement.spacedBy(layout.gap),
                        verticalArrangement = Arrangement.spacedBy(layout.gap),
                        modifier = Modifier.fillMaxSize().focusGroup().gridDpadNavigation(gridFocus).rightStickScroll(state),
                    ) {
                        itemsIndexed(list, key = { _, it -> it.key }) { index, c ->
                            CollectionCard(c, base, modifier = Modifier.gridFocusItem(index, gridFocus)) {
                                onOpenLibrary(LibraryRoute.collection(c.toSource(), c.name))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(c: Collection, base: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = RommTheme.colors
    val shape = MaterialTheme.shapes.large
    val covers = remember(c, base) { c.coverUrls(base) }
    Column(
        modifier
            .gamepadFocus(shape)
            .clip(shape)
            .background(colors.surface)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)) {
            when {
                covers.size >= 4 -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) { CoverImage(covers[0], null, Modifier.weight(1f).fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape); CoverImage(covers[1], null, Modifier.weight(1f).fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) }
                    Row(Modifier.weight(1f)) { CoverImage(covers[2], null, Modifier.weight(1f).fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape); CoverImage(covers[3], null, Modifier.weight(1f).fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) }
                }
                covers.isNotEmpty() -> CoverImage(covers[0], null, Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape)
                else -> CoverImage(null, null, Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape)
            }
            StatusPill(c.romCount.toString(), colors.primaryDarken, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Column(Modifier.padding(8.dp)) {
            Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            if (!c.description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(c.description, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GridSkeleton() {
    val layout = RommTheme.layout
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / (layout.coverMinCell + 24.dp)).toInt().coerceIn(2, layout.maxColumns)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(layout.padding),
            horizontalArrangement = Arrangement.spacedBy(layout.gap),
            verticalArrangement = Arrangement.spacedBy(layout.gap),
            userScrollEnabled = false,
        ) {
            items(8) { Column { Skeleton(Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.large); Spacer(Modifier.height(6.dp)); Skeleton(Modifier.fillMaxWidth(0.7f).height(12.dp), MaterialTheme.shapes.small) } }
        }
    }
}
