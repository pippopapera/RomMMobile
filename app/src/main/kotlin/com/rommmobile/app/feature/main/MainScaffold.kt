package com.rommmobile.app.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import com.rommmobile.app.R
import com.rommmobile.app.core.design.NavMode
import com.rommmobile.app.core.design.PadBar
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.StatusPill
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.input.GamepadAction
import com.rommmobile.app.feature.collections.CollectionsScreen
import com.rommmobile.app.feature.downloads.DownloadMiniBar
import com.rommmobile.app.feature.platforms.LocalPlatformsScreen
import com.rommmobile.app.feature.platforms.PlatformsScreen
import com.rommmobile.app.feature.search.SearchScreen

private enum class Section(val icon: ImageVector, val labelRes: Int) {
    PLATFORMS(Icons.Rounded.SportsEsports, R.string.section_platforms),
    COLLECTIONS(Icons.Rounded.Collections, R.string.section_collections),
    LOCAL(Icons.Rounded.SdStorage, R.string.section_local),
    SEARCH(Icons.Rounded.Search, R.string.section_search),
}

/**
 * Shell for the three sections. Rail on the left (D-pad side) for short / wide screens,
 * bottom bar only on tall compact phones. L1/R1 cycle sections, R3 opens the queue.
 */
@Composable
fun MainScaffold(
    appVm: AppViewModel,
    onOpenLibrary: (LibraryRoute) -> Unit,
    onOpenGame: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val layout = RommTheme.layout
    val colors = RommTheme.colors
    var sectionIndex by rememberSaveable { mutableIntStateOf(0) }
    val section = Section.entries[sectionIndex]
    // One level up, like everywhere else: from Collections, Installed or Search, B returns to
    // Platforms; only from Platforms does it arm the exit. Registered here, inside the Home
    // destination, so it takes priority over the root handler while it is enabled.
    BackHandler(enabled = sectionIndex != 0) { sectionIndex = 0 }
    val holder = rememberSaveableStateHolder()
    val openCount by appVm.openDownloadCount.collectAsStateWithLifecycle()

    // Silent by design: the rail highlights the section L1/R1 move between, and the queue bar
    // shows where R3 lands. Nothing is drawn here; the shell has no bar of its own.
    PadBar(sectionIndex) {
        silent(GamepadAction.PREV_SECTION) { sectionIndex = (sectionIndex + Section.entries.size - 1) % Section.entries.size }
        silent(GamepadAction.NEXT_SECTION) { sectionIndex = (sectionIndex + 1) % Section.entries.size }
        silent(GamepadAction.OPEN_SEARCH) { sectionIndex = Section.SEARCH.ordinal }
        silent(GamepadAction.OPEN_DOWNLOADS) { onOpenDownloads() }
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            holder.SaveableStateProvider(section.name) {
                when (section) {
                    Section.PLATFORMS -> PlatformsScreen(appVm = appVm, onOpenLibrary = onOpenLibrary, onOpenGame = onOpenGame, onOpenDownloads = onOpenDownloads)
                    Section.COLLECTIONS -> CollectionsScreen(onOpenLibrary = onOpenLibrary)
                    Section.LOCAL -> LocalPlatformsScreen(appVm = appVm, onOpenLibrary = onOpenLibrary)
                    Section.SEARCH -> SearchScreen(onOpenGame = onOpenGame, onOpenDownloads = onOpenDownloads)
                }
            }
        }
    }

    if (layout.navMode == NavMode.BOTTOM_BAR) {
        Column(Modifier.fillMaxSize()) {
            content(Modifier.weight(1f).fillMaxWidth())
            DownloadMiniBar(onClick = onOpenDownloads)
            BottomBar(
                selected = sectionIndex,
                onSelect = { sectionIndex = it },
                openCount = openCount,
                onOpenDownloads = onOpenDownloads,
                onOpenSettings = onOpenSettings,
            )
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Rail(
                mode = layout.navMode,
                selected = sectionIndex,
                onSelect = { sectionIndex = it },
                openCount = openCount,
                onOpenDownloads = onOpenDownloads,
                onOpenSettings = onOpenSettings,
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                content(Modifier.weight(1f).fillMaxWidth())
                DownloadMiniBar(onClick = onOpenDownloads)
            }
        }
    }
}

@Composable
private fun Rail(
    mode: NavMode,
    selected: Int,
    onSelect: (Int) -> Unit,
    openCount: Int,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = RommTheme.colors
    val requesters = remember { List(Section.entries.size) { FocusRequester() } }
    Column(
        Modifier
            .fillMaxHeight()
            .width(mode.railWidth)
            .background(colors.surface)
            // Moving into the rail lands on the section that is already highlighted, never on the
            // first item: a cursor on "Platforms" while "Installed" is lit is two answers to one
            // question.
            .focusProperties { onEnter = { requesters[selected].requestFocus() } }
            .focusGroup()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Section.entries.forEachIndexed { i, s ->
            RailItem(
                icon = s.icon,
                label = stringResource(s.labelRes),
                selected = selected == i,
                showLabel = mode.showLabels,
                onClick = { onSelect(i) },
                modifier = Modifier.focusRequester(requesters[i]),
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        RailItem(
            icon = Icons.Rounded.Download,
            label = stringResource(R.string.section_downloads),
            selected = false,
            showLabel = mode.showLabels,
            badge = openCount.takeIf { it > 0 }?.toString(),
            onClick = onOpenDownloads,
        )
        Spacer(Modifier.height(4.dp))
        RailItem(
            icon = Icons.Rounded.Settings,
            label = stringResource(R.string.section_settings),
            selected = false,
            showLabel = mode.showLabels,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = RommTheme.colors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            // Scale alone says where the cursor is, so it is a touch stronger than on a cover; a
            // label sliding in underneath only made the item jump in height on every pass.
            .gamepadFocus(shape, scale = 1.12f)
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.22f) else colors.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, contentDescription = label, tint = if (selected) colors.primaryLighten else colors.onSurface, modifier = Modifier.size(24.dp))
            if (badge != null) {
                StatusPill(badge, colors.primary, modifier = Modifier.align(Alignment.TopEnd).padding(start = 14.dp))
            }
        }
        if (showLabel) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.primaryLighten else colors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun BottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    openCount: Int,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .height(layout.bottomBarHeight)
            .focusGroup()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Section.entries.forEachIndexed { i, s ->
            BottomItem(s.icon, stringResource(s.labelRes), selected == i, Modifier.weight(1f)) { onSelect(i) }
        }
        BottomItem(Icons.Rounded.Download, stringResource(R.string.section_downloads), false, Modifier.weight(1f), badge = openCount.takeIf { it > 0 }?.toString(), onClick = onOpenDownloads)
        BottomItem(Icons.Rounded.Settings, stringResource(R.string.section_settings), false, Modifier.weight(1f), onClick = onOpenSettings)
    }
}

@Composable
private fun BottomItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, badge: String? = null, onClick: () -> Unit) {
    val colors = RommTheme.colors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .gamepadFocus(shape, scale = 1.12f)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(CircleShape)
                .background(if (selected) colors.primary.copy(alpha = 0.22f) else colors.surface)
                .padding(horizontal = 14.dp, vertical = 3.dp)
        ) {
            Icon(icon, contentDescription = label, tint = if (selected) colors.primaryLighten else colors.onSurface, modifier = Modifier.size(24.dp))
            if (badge != null) StatusPill(badge, colors.primary, modifier = Modifier.align(Alignment.TopEnd))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) colors.primaryLighten else colors.onSurfaceMuted, maxLines = 1)
    }
}
