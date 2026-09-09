package com.rommmobile.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.data.prefs.Settings

/**
 * Per-platform overrides the spec asks for (doc 05 §5.2/5.3): one folder per game on disc
 * systems, and unpacking rules that differ from the global default.
 */
@Composable
fun PerPlatformDialog(vm: SettingsViewModel, settings: Settings, onDismiss: () -> Unit) {
    val platforms by vm.platforms.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    RommDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_per_platform),
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        dismissText = null,
        focusConfirm = false,
    ) {
        Column {
            Text(stringResource(R.string.per_platform_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(platforms, key = { it.id }) { p ->
                    val folderPerGame = p.slug in settings.folderPerGameSlugs
                    val override = settings.extractOverrides[p.slug]
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.per_platform_folder), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = folderPerGame, onCheckedChange = { vm.setFolderPerGame(p.slug, it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf<Pair<Boolean?, Int>>(
                                null to R.string.per_platform_default,
                                true to R.string.per_platform_extract,
                                false to R.string.per_platform_keep,
                            ).forEach { (value, label) ->
                                val selected = override == value
                                Text(
                                    stringResource(label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) colors.white else colors.onSurfaceMuted,
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .gamepadFocusRing(PillShape)
                                        .clip(PillShape)
                                        .background(if (selected) colors.primary else colors.surface)
                                        .clickable { vm.setExtractOverride(p.slug, value) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

