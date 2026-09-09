package com.rommmobile.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.core.input.rememberHasGamepad
import com.rommmobile.app.R
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadTextField
import com.rommmobile.app.data.db.RecentSearchDao
import com.rommmobile.app.data.db.RecentSearchEntity
import com.rommmobile.app.data.repo.LibrarySource
import com.rommmobile.app.feature.library.LibraryContent
import com.rommmobile.app.feature.library.libraryViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val dao: RecentSearchDao) : ViewModel() {
    val recent: StateFlow<List<String>> = dao.observeRecent().map { l -> l.map { it.term } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun remember(term: String) = viewModelScope.launch { if (term.length >= 2) dao.upsert(RecentSearchEntity(term.trim(), System.currentTimeMillis())) }
    fun forget(term: String) = viewModelScope.launch { dao.delete(term) }
}

@Composable
fun SearchScreen(onOpenGame: (Int) -> Unit, onOpenDownloads: () -> Unit) {
    val searchVm: SearchViewModel = hiltViewModel()
    val vm = libraryViewModel(LibrarySource.Search)
    val recent by searchVm.recent.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(state.searchTerm) }
    var showSort by remember { mutableStateOf(false) }
    val fieldFr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val layout = RommTheme.layout
    val colors = RommTheme.colors

    // Only when there is no pad. On a handheld this focus is what threw the keyboard over the
    // screen the moment the tab opened, with no obvious way back out of it.
    val hasPad by rememberHasGamepad()
    LaunchedEffect(hasPad) { if (!hasPad && text.isEmpty()) runCatching { fieldFr.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.section_search))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; vm.setSearchTerm(it) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (text.isNotEmpty()) IconButton(onClick = { text = ""; vm.setSearchTerm("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); searchVm.remember(text) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = layout.padding, vertical = 4.dp).focusRequester(fieldFr).gamepadTextField(MaterialTheme.shapes.medium),
        )
        if (text.trim().length < 2) {
            if (recent.isEmpty()) {
                EmptyState(Icons.Rounded.Search, stringResource(R.string.search_hint_title), subtitle = stringResource(R.string.search_hint_body))
            } else {
                SectionHeader(stringResource(R.string.search_recent))
                FlowRow(Modifier.padding(horizontal = layout.padding), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { term ->
                        Row(
                            Modifier
                                .gamepadFocusRing(PillShape)
                                .clip(PillShape)
                                .background(colors.surface)
                                .clickable { text = term; vm.setSearchTerm(term) }
                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, null, tint = colors.onSurfaceMuted, modifier = Modifier.height(14.dp))
                            Spacer(Modifier.padding(2.dp))
                            Text(term, style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { searchVm.forget(term) }, modifier = Modifier.height(24.dp)) { Icon(Icons.Rounded.Close, null, modifier = Modifier.height(12.dp)) }
                        }
                    }
                }
            }
        } else {
            LaunchedEffect(text) { kotlinx.coroutines.delay(1500); searchVm.remember(text) }
            LibraryContent(
                vm = vm,
                modifier = Modifier.weight(1f),
                showPlatform = true,
                onOpenGame = onOpenGame,
                onOpenDownloads = onOpenDownloads,
                showSort = showSort,
                onShowSort = { showSort = it },
                emptyMessage = stringResource(R.string.search_no_results, text),
                handleDownloadsShortcut = false,
            )
        }
    }
}
