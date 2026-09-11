package com.rommmobile.app.feature.controls

import com.rommmobile.app.core.design.Toaster
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.input.ButtonMap
import com.rommmobile.app.core.input.LogicalButton
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.prefs.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ButtonMapViewModel @Inject constructor(
    private val store: SettingsStore,
    private val handle: SavedStateHandle,
    private val log: FileLogger,
    private val toaster: Toaster,
) : ViewModel() {
    /** The card closes the frame its single button is recorded; what happened is said afterwards. */
    fun announce(text: String) = toaster.show(text)

    val map: StateFlow<ButtonMap> = store.buttonMap.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ButtonMap.DEFAULT)

    /**
     * The map before the last change made on this screen: one step back, no history. Kept in
     * saved state so a process death while the user reads something else does not take the
     * undo with it.
     */
    val previous: StateFlow<ButtonMap?> = handle.getStateFlow<String?>(KEY_PREVIOUS, null)
        .map { it?.let(ButtonMap::decode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), handle.get<String>(KEY_PREVIOUS)?.let(ButtonMap::decode))

    fun set(next: ButtonMap) = viewModelScope.launch {
        val current = store.snapshot().buttonMap
        if (current == next) return@launch
        handle[KEY_PREVIOUS] = current.encode()
        store.setButtonMap(next.completed())
        log.i("Input", "button map ${next.completed().encode()}")
    }

    fun undo() = viewModelScope.launch {
        val back = handle.get<String>(KEY_PREVIOUS)?.let(ButtonMap::decode) ?: return@launch
        handle[KEY_PREVIOUS] = null
        store.setButtonMap(back)
    }

    fun reset() = set(ButtonMap.DEFAULT)

    private companion object { const val KEY_PREVIOUS = "prev_button_map" }
}

/**
 * RetroArch-style table: one row per printed button with the key it is bound to. A on a row
 * records that button alone; the footer redoes the whole guided pass, restores the default, or
 * takes back the last change.
 */
@Composable
fun ButtonMapScreen(onBack: () -> Unit) {
    val vm: ButtonMapViewModel = hiltViewModel()
    val map by vm.map.collectAsStateWithLifecycle()
    val previous by vm.previous.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var capture by remember { mutableStateOf<CaptureSession?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            RommTopBar(
                title = stringResource(R.string.settings_button_map),
                subtitle = stringResource(if (map.isDefault) R.string.settings_button_map_default else R.string.settings_button_map_custom),
                onBack = onBack,
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().rightStickScroll(listState), contentPadding = PaddingValues(4.dp)) {
                item {
                    Text(
                        stringResource(R.string.controls_table_intro),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                items(LogicalButton.entries, key = { it.name }) { button ->
                    val code = map.codeFor(button)
                    SettingRow(
                        title = button.label(),
                        subtitle = if (code != null) ButtonMap.describe(code) else stringResource(R.string.controls_not_assigned),
                        // A single button starts from the live map, so giving it a key another
                        // row holds swaps the two: the A/B swap of a Nintendo-layout pad in one go.
                        onClick = { capture = CaptureSession(listOf(button), map, refuseDuplicates = false) },
                    )
                }
                item { SectionHeader(stringResource(R.string.controls_table_actions)) }
                item {
                    SettingRow(
                        title = stringResource(R.string.controls_redo_sequence),
                        subtitle = stringResource(R.string.controls_redo_sequence_hint),
                        onClick = { capture = CaptureSession(CaptureSession.FULL_ORDER, ButtonMap.EMPTY, refuseDuplicates = true) },
                    )
                }
                item { SettingRow(title = stringResource(R.string.controls_reset), enabled = !map.isDefault, onClick = { vm.reset() }) }
                item { SettingRow(title = stringResource(R.string.controls_undo_change), enabled = previous != null, onClick = { vm.undo() }) }
            }
        }
        capture?.let { session ->
            ButtonCaptureOverlay(
                session = session,
                onFinished = { next ->
                    val fb = session.feedback
                    vm.set(next)
                    capture = null
                    when (fb) {
                        is CaptureFeedback.Swapped -> vm.announce(context.getString(R.string.controls_swapped, fb.button.label(context), fb.other.label(context)))
                        is CaptureFeedback.Cleared -> vm.announce(context.getString(R.string.controls_cleared, fb.owner.label(context)))
                        else -> Unit
                    }
                },
                onCancel = { capture = null },
            )
        }
    }
}
