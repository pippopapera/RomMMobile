package com.rommmobile.app.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusRow
import com.rommmobile.app.core.design.gamepadTextField
import com.rommmobile.app.data.mapping.Resolution
import com.rommmobile.app.data.repo.Rom

/** The one question the user ever sees about folders, once per platform. */
@Composable
fun FolderDialog(
    pending: PendingFolder,
    onCreate: (String) -> Unit,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RommTheme.colors
    when (val res = pending.resolution) {
        is Resolution.NeedsCreate -> {
            var name by remember(res) { mutableStateOf(res.proposedName) }
            RommDialog(
                onDismissRequest = onDismiss,
                title = stringResource(R.string.folder_create_title, pending.platform.name),
                confirmText = stringResource(R.string.folder_create_confirm),
                onConfirm = { onCreate(name) },
            ) {
                Column {
                    Text(stringResource(R.string.folder_create_body, name), style = MaterialTheme.typography.bodyMedium)
                    if (!res.supportedByLauncher) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.folder_create_unsupported), style = MaterialTheme.typography.bodySmall, color = colors.accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.replace(Regex("[\\\\/:*?\"<>|]"), "") },
                        singleLine = true,
                        label = { Text(stringResource(R.string.folder_name_label)) },
                        modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small),
                    )
                }
            }
        }
        is Resolution.Ambiguous -> {
            var chosen by remember(res) { mutableStateOf(res.preselected) }
            RommDialog(
                onDismissRequest = onDismiss,
                title = stringResource(R.string.folder_ambiguous_title, pending.platform.name),
                confirmText = stringResource(R.string.action_use_this),
                onConfirm = { onChoose(chosen) },
                focusConfirm = false,
            ) {
                Column {
                    Text(stringResource(R.string.folder_ambiguous_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    res.candidates.forEach { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .gamepadFocusRow(MaterialTheme.shapes.small)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { chosen = c.relDir }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == c.relDir, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(c.relDir, style = MaterialTheme.typography.bodyLarge)
                                Text(pluralStringResource(R.plurals.n_files, c.fileCount, c.fileCount), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
                            }
                        }
                    }
                }
            }
        }
        else -> onDismiss()
    }
}

@Composable
fun RedownloadDialog(rom: Rom, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RommDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.redownload_title),
        confirmText = stringResource(R.string.action_download_again),
        onConfirm = onConfirm,
        focusConfirm = false,
    ) {
        Text(stringResource(R.string.redownload_body, rom.fsName), style = MaterialTheme.typography.bodyMedium)
    }
}
