package com.rommmobile.app.feature.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.StatusPill
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.repo.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MiniBarState(
    val visible: Boolean = false,
    val title: String = "",
    val percent: Int = 0,
    val speed: Long = 0,
    val queued: Int = 0,
    val paused: Boolean = false,
)

@HiltViewModel
class MiniBarViewModel @Inject constructor(downloads: DownloadRepository) : ViewModel() {
    val state: StateFlow<MiniBarState> = combine(downloads.observeOpen(), downloads.progress) { open, prog ->
        if (open.isEmpty()) return@combine MiniBarState()
        val active = open.firstOrNull { it.state.isActive } ?: open.firstOrNull { it.state == DownloadState.PENDING } ?: open.first()
        val p = prog[active.id]
        MiniBarState(
            visible = true,
            title = active.title,
            percent = p?.percent ?: Format.percent(active.downloadedBytes, active.totalBytes),
            speed = prog.values.sumOf { it.bytesPerSecond },
            queued = open.size - 1,
            paused = open.all { it.state == DownloadState.PAUSED },
        )
    }.sample(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiniBarState())
}

/** 44 dp `toplayer` strip above the navigation, only while the queue is not empty. */
@Composable
fun DownloadMiniBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val vm: MiniBarViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    AnimatedVisibility(visible = state.visible, modifier = modifier, enter = expandVertically(), exit = shrinkVertically()) {
        Column(
            Modifier
                .fillMaxWidth()
                .gamepadFocusRing(MaterialTheme.shapes.small, ringWidthDp = 2)
                .background(colors.toplayer)
                .clickable(onClick = onClick)
                .height(layout.miniBarHeight),
        ) {
            Row(Modifier.weight(1f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (state.paused) Icons.Rounded.Pause else Icons.Rounded.Download, contentDescription = null, tint = colors.primaryLighten, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(state.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (state.paused) {
                    Text(stringResource(R.string.state_paused), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
                } else {
                    Text("${state.percent}%", style = MaterialTheme.typography.labelMedium, color = colors.primaryLighten)
                    if (state.speed > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(Format.speed(state.speed), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted)
                    }
                }
                if (state.queued > 0) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill("+${state.queued}", colors.primaryDarken)
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = colors.primary, trackColor = colors.surface, drawStopIndicator = {})
            }
        }
    }
}
