package com.rommmobile.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rommmobile.app.core.design.Badge
import com.rommmobile.app.core.design.CoverImage
import com.rommmobile.app.core.design.PlatformIcon
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.Skeleton
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusRow
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.feature.downloads.engine.DownloadProgress

/** Per-item status derived once per recomposition of the parent; cheap to compare. */
@Immutable
data class ItemStatus(val present: Boolean, val progress: DownloadProgress?) {
    companion object { val NONE = ItemStatus(false, null) }
}

const val COVER_RATIO = 1f / 1.4f

@Composable
fun GameCard(
    rom: Rom,
    status: ItemStatus,
    showPlatform: Boolean,
    platformIconUrl: String?,
    onFocused: (Rom) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    largeCover: Boolean = false,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val shape = MaterialTheme.shapes.medium
    // RomM generates the "small" cover at a fifth of the original, around 162x216 px. Stretched
    // over a grid cell it turns the title art into mush, which is what shows up on box scans.
    // Anything above a thumbnail therefore asks for the 1080p original and lets Coil subsample.
    val coverUrl = if (largeCover) rom.coverLarge ?: rom.coverSmall else rom.coverSmall
    Column(
        modifier
            .gamepadFocus(shape, onFocused = { if (it) onFocused(rom) })
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(COVER_RATIO)) {
            CoverImage(url = coverUrl, contentDescription = rom.name, modifier = Modifier.fillMaxSize(), shape = shape)
            rom.regionBadge?.let { Badge(it, Modifier.align(Alignment.TopStart).padding(4.dp)) }
            if (showPlatform) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(RommTheme.colors.translucent, CircleShape), contentAlignment = Alignment.Center) {
                    PlatformIcon(platformIconUrl, size = 16.dp)
                }
            }
            if (rom.siblingCount > 0) {
                Badge("${rom.siblingCount + 1}", Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
            StatusOverlay(status, Modifier.align(Alignment.Center), presentAlign = Alignment.BottomStart, boxModifier = Modifier.matchParentSize())
            // Only while selecting, and only top-right: every other corner already carries
            // something (region, platform, sibling count, the presence tick).
            if (selecting) {
                val colors = RommTheme.colors
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                        .background(if (selected) colors.primary else colors.translucent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            rom.name,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun StatusOverlay(status: ItemStatus, modifier: Modifier, presentAlign: Alignment, boxModifier: Modifier) {
    val colors = RommTheme.colors
    val p = status.progress
    Box(boxModifier) {
        when {
            p != null && (p.state.isActive || p.state == DownloadState.PENDING || p.state == DownloadState.PAUSED || p.state == DownloadState.FAILED) -> {
                Box(
                    Modifier.align(Alignment.Center).size(46.dp).background(colors.translucent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    when (p.state) {
                        DownloadState.PENDING -> Icon(Icons.Rounded.Schedule, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        DownloadState.PAUSED -> Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        DownloadState.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(22.dp))
                        DownloadState.DOWNLOADING -> {
                            CircularProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.size(40.dp), color = colors.primaryLighten, trackColor = colors.gray, strokeWidth = 3.dp)
                            Text("${p.percent}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                        else -> CircularProgressIndicator(modifier = Modifier.size(40.dp), color = colors.primaryLighten, strokeWidth = 3.dp)
                    }
                }
            }
            status.present -> {
                Box(
                    Modifier.align(presentAlign).padding(4.dp).size(20.dp).background(colors.green, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

@Composable
fun GameCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(COVER_RATIO))
        Spacer(Modifier.height(6.dp))
        Skeleton(Modifier.fillMaxWidth(0.8f).height(12.dp), shape = MaterialTheme.shapes.small)
        Spacer(Modifier.height(4.dp))
        Skeleton(Modifier.fillMaxWidth(0.5f).height(12.dp), shape = MaterialTheme.shapes.small)
    }
}

@Composable
fun GameRow(
    rom: Rom,
    status: ItemStatus,
    showPlatform: Boolean,
    onFocused: (Rom) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.hasFocus) onFocused(rom) }
            .gamepadFocusRow(shape)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .height(layout.listRowHeight - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(rom.coverSmall, rom.name, Modifier.width(40.dp).fillMaxSize(), shape = MaterialTheme.shapes.small)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(rom.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                if (showPlatform) add(rom.platformName)
                rom.regionBadge?.let { add(it) }
                if (rom.sizeBytes > 0) add(Format.bytes(rom.sizeBytes))
                if (rom.siblingCount > 0) add("${rom.siblingCount + 1} ver.")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (selecting) {
            // Takes the status slot: while selecting, whether it is picked is the only status
            // that matters, and the tick is where the eye already goes on a row.
            Box(
                Modifier.size(24.dp).background(if (selected) colors.primary else colors.toplayer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            RowStatus(status)
        }
    }
}

@Composable
private fun RowStatus(status: ItemStatus) {
    val colors = RommTheme.colors
    val p = status.progress
    when {
        p != null && p.state == DownloadState.DOWNLOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${p.percent}%", style = MaterialTheme.typography.labelMedium, color = colors.primaryLighten)
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primaryLighten, trackColor = colors.gray)
        }
        p != null && p.state.isActive -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primaryLighten)
        p != null && p.state == DownloadState.PENDING -> Icon(Icons.Rounded.Schedule, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(18.dp))
        p != null && p.state == DownloadState.PAUSED -> Icon(Icons.Rounded.Pause, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(18.dp))
        p != null && p.state == DownloadState.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(18.dp))
        status.present -> Icon(Icons.Rounded.Check, null, tint = colors.green, modifier = Modifier.size(20.dp))
        else -> Icon(Icons.Rounded.Download, null, tint = colors.gray, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun GameRowSkeleton(modifier: Modifier = Modifier) {
    val layout = RommTheme.layout
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).height(layout.listRowHeight - 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Skeleton(Modifier.width(40.dp).fillMaxSize(), shape = MaterialTheme.shapes.small)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp), shape = MaterialTheme.shapes.small)
            Spacer(Modifier.height(6.dp))
            Skeleton(Modifier.fillMaxWidth(0.35f).height(11.dp), shape = MaterialTheme.shapes.small)
        }
    }
}
