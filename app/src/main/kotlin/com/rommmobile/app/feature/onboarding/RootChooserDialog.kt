package com.rommmobile.app.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadTextField
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.core.storage.StorageRoot
import java.io.File

/**
 * Picking the ROM folder goes through the system folder picker, the one the user already knows
 * from every other app. The typed-path field only appears where no picker exists (some TV
 * boxes and Fire TV), which is the case the spec calls out as a dead end.
 *
 * With All files access granted, the folder the picker returns is converted back into a plain
 * path, so downloads are written directly instead of through SAF.
 */
@Composable
fun RootChooserDialog(
    preferredNames: List<String>,
    onChosen: (StorageRoot) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.root_title),
) {
    val context = LocalContext.current
    val colors = RommTheme.colors
    var allFiles by remember { mutableStateOf(StorageLocations.hasAllFilesAccess(context)) }
    var manual by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    val canPick = remember { StorageLocations.canOpenTree(context) }

    // Re-check the permission when coming back from the system settings screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allFiles = StorageLocations.hasAllFilesAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            StorageLocations.persistTree(context, uri)
            onChosen(StorageLocations.rootFromTree(context, uri))
        }
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    RommDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = stringResource(if (canPick) R.string.root_pick_saf else R.string.action_use_path),
        onConfirm = {
            if (canPick) {
                runCatching { treeLauncher.launch(null) }
            } else {
                val f = File(manual.trim())
                when {
                    manual.isBlank() -> manualError = context.getString(R.string.root_manual_empty)
                    !(f.isDirectory || f.mkdirs()) -> manualError = context.getString(R.string.root_manual_invalid)
                    else -> onChosen(StorageRoot.Direct(f.absolutePath))
                }
            }
        },
    ) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.root_pick_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            if (!allFiles) {
                Text(stringResource(R.string.root_allfiles_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
                Spacer(Modifier.height(6.dp))
                val intent = remember { StorageLocations.allFilesAccessIntent(context) }
                if (intent != null) {
                    Button(onClick = { settingsLauncher.launch(intent) }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.root_allfiles_grant))
                    }
                } else {
                    Text(stringResource(R.string.root_allfiles_unavailable), style = MaterialTheme.typography.bodySmall, color = colors.accent)
                }
                Spacer(Modifier.height(10.dp))
            }
            if (!canPick) {
                Text(stringResource(R.string.root_no_picker), style = MaterialTheme.typography.bodySmall, color = colors.accent)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it; manualError = null },
                    singleLine = true,
                    placeholder = { Text("/storage/emulated/0/ROMs") },
                    isError = manualError != null,
                    supportingText = manualError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small),
                )
            }
        }
    }
}
