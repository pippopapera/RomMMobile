package com.rommmobile.app.feature.platforms

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.dp
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusIcon
import com.rommmobile.app.feature.main.AppViewModel
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rommmobile.app.core.input.rememberHasGamepad
import kotlinx.coroutines.delay
import com.rommmobile.app.R
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.input.GridBringIntoViewSpec
import com.rommmobile.app.core.input.gridDpadNavigation
import com.rommmobile.app.core.input.gridFocusItem
import com.rommmobile.app.core.input.rememberGridFocusCoordinator
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.Platform
import com.rommmobile.app.data.repo.PlatformRepository
import com.rommmobile.app.feature.main.LibraryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A platform plus how much of it is actually sitting on this device. */
data class LocalPlatform(val platform: Platform, val count: Int, val bytes: Long)

@HiltViewModel
class LocalPlatformsViewModel @Inject constructor(
    local: LocalLibraryRepository,
    platforms: PlatformRepository,
    serverStore: ServerStore,
) : ViewModel() {
    /**
     * Only platforms with at least one downloaded file, so the section fills up as the user
     * downloads and empties itself again when they delete the last game of a platform.
     */
    val items: StateFlow<List<LocalPlatform>> = combine(local.summary(), platforms.platforms) { rows, plats ->
        val bySlug = plats.associateBy { it.slug }
        rows.filter { it.count > 0 }
            .mapNotNull { row -> bySlug[row.platformSlug]?.let { LocalPlatform(it, row.count, row.bytes) } }
            .sortedBy { it.platform.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val baseUrl: StateFlow<String?> = serverStore.config.map { it.activeUrl }
        .stateIn(viewModelScope, SharingStarted.Eagerly, serverStore.config.value.activeUrl)
}

/** "On this device": the same grid as Platforms, restricted to what has been downloaded. */
@Composable
fun LocalPlatformsScreen(appVm: AppViewModel, onOpenLibrary: (LibraryRoute) -> Unit) {
    val vm: LocalPlatformsViewModel = hiltViewModel()
    val syncing by appVm.syncing.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val base by vm.baseUrl.collectAsStateWithLifecycle()
    val layout = RommTheme.layout
    val state = rememberLazyGridState()
    val gridFocus = rememberGridFocusCoordinator(state)
    val hasGamepad by rememberHasGamepad()

    Column(Modifier.fillMaxSize()) {
        RommTopBar(
            title = stringResource(R.string.section_local),
            subtitle = if (items.isEmpty()) null else stringResource(
                R.string.local_total,
                items.sumOf { it.count },
                Format.bytes(items.sumOf { it.bytes }),
            ),
            actions = {
                // The scan runs at start-up, but a card can be swapped or files copied over from a
                // PC while the app is open, so there is a way to ask for it by hand.
                IconButton(
                    onClick = { appVm.syncLocalLibrary() },
                    enabled = !syncing,
                    modifier = Modifier.gamepadFocusIcon(),
                ) {
                    if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = RommTheme.colors.primaryLighten)
                    else Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_sync))
                }
            },
        )
        if (items.isEmpty()) {
            EmptyState(Icons.Rounded.SdStorage, stringResource(R.string.local_empty), subtitle = stringResource(R.string.local_section_hint))
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = (maxWidth / layout.platformMinCell).toInt().coerceIn(2, layout.maxColumns)
                LaunchedEffect(columns, items.size) {
                    gridFocus.columns = columns
                    gridFocus.itemCount = items.size
                }
                // Same contract as the Platforms grid: coming back from a library lands on the card
                // the user left, not on the first thing in the layout - which was the first item of
                // the rail, i.e. a different section from the one highlighted as current.
                var lastFocused by rememberSaveable { mutableIntStateOf(0) }
                var restored by remember { mutableStateOf(false) }
                LaunchedEffect(items.size) {
                    if (items.isEmpty()) return@LaunchedEffect
                    val target = lastFocused.coerceIn(0, items.lastIndex)
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
                        itemsIndexed(items, key = { _, it -> it.platform.id }) { index, item ->
                            PlatformCard(
                                // Counts and size describe what is HERE, not what the server holds.
                                platform = item.platform.copy(romCount = item.count, sizeBytes = item.bytes),
                                base = base,
                                onClick = {
                                    onOpenLibrary(
                                        LibraryRoute.platform(item.platform.id, item.platform.slug, item.platform.name)
                                            .copy(onlyPresent = true)
                                    )
                                },
                                modifier = Modifier.gridFocusItem(index, gridFocus),
                            )
                        }
                    }
                }
            }
        }
    }
}
