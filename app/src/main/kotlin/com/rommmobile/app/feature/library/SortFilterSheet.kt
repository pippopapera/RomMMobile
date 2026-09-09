package com.rommmobile.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusRow
import com.rommmobile.app.data.prefs.SortField

val SortField.labelRes: Int
    get() = when (this) {
        SortField.NAME -> R.string.sort_name
        SortField.RELEASE_DATE -> R.string.sort_release
        SortField.RATING -> R.string.sort_rating
        SortField.SIZE -> R.string.sort_size
        SortField.ADDED -> R.string.sort_added
    }


/** Sorting and filters as a dialog: fully focus-trapped, one B press to leave. */
@Composable
fun SortFilterDialog(
    state: LibraryUiState,
    onSort: (SortField, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFr.requestFocus() } }
    RommDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.sort_title),
        confirmText = stringResource(R.string.action_done),
        onConfirm = onDismiss,
        dismissText = null,
        focusConfirm = false,
    ) {
        // No section header: the dialog holds one group and its title already names it.
        Column {
            SortField.entries.forEachIndexed { i, f ->
                ChoiceRow(
                    label = stringResource(f.labelRes),
                    selected = state.sort == f,
                    modifier = if (i == 0) Modifier.focusRequester(firstFr) else Modifier,
                    // Each field has one sensible direction (newest, highest, biggest, A to Z),
                    // so there is no toggle and nothing to explain with an arrow.
                    onClick = { onSort(f, f != SortField.NAME) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .gamepadFocusRow(MaterialTheme.shapes.small)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
