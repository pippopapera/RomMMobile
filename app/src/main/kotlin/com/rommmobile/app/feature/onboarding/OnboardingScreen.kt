package com.rommmobile.app.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PadButton
import com.rommmobile.app.core.design.PadGlyph
import com.rommmobile.app.core.design.QrImage
import com.rommmobile.app.core.input.rememberHasGamepad
import kotlinx.coroutines.delay
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SwitchRow
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadTextField
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.data.prefs.Launcher
import com.rommmobile.app.data.repo.Session
import com.rommmobile.app.feature.settings.MappingEditDialog
import com.rommmobile.app.feature.settings.MappingRow
import com.rommmobile.app.feature.settings.MappingStatus

@Composable
fun launcherLabel(l: Launcher): String = when (l) {
    Launcher.ESDE -> "ES-DE"
    Launcher.COCOON -> "Cocoon"
    Launcher.DAIJISHO -> "Daijishō"
    Launcher.BEACON -> "Beacon"
    Launcher.IISU -> "iiSU"
    Launcher.PEGASUS -> "Pegasus"
    Launcher.CUSTOM -> stringResource(R.string.launcher_custom)
}

fun launcherDescRes(l: Launcher): Int = when (l) {
    Launcher.ESDE -> R.string.launcher_esde_desc
    Launcher.COCOON -> R.string.launcher_cocoon_desc
    Launcher.DAIJISHO -> R.string.launcher_daijisho_desc
    Launcher.BEACON -> R.string.launcher_beacon_desc
    Launcher.IISU -> R.string.launcher_iisu_desc
    Launcher.PEGASUS -> R.string.launcher_pegasus_desc
    Launcher.CUSTOM -> R.string.launcher_custom_desc
}

private val STEP_TITLES = listOf(R.string.ob_step_server, R.string.ob_step_auth, R.string.ob_step_launcher, R.string.ob_step_storage, R.string.ob_step_summary)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val vm: OnboardingViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val layout = RommTheme.layout
    val colors = RommTheme.colors
    val hasGamepad by rememberHasGamepad()

    BackHandler(enabled = state.step > 0) { vm.goTo(state.step - 1) }
    LaunchedEffect(state.step) { if (state.step == 4) vm.buildSummary() }

    val canNext = when (state.step) {
        0 -> state.probe is ProbeState.Ok
        1 -> session is Session.LoggedIn
        2 -> true
        // A folder alone is not enough: a root kept from an earlier setup shows up as "not
        // writable" once All files access is missing, and Next used to sail past it.
        3 -> state.root != null && state.rootWritable == true
        else -> true
    }

    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(STEP_TITLES[state.step]), subtitle = stringResource(R.string.ob_step_n, state.step + 1, 5), onBack = if (state.step > 0) ({ vm.goTo(state.step - 1) }) else null)
        LinearProgressIndicator(progress = { (state.step + 1) / 5f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.primary, trackColor = colors.surface, drawStopIndicator = {})
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.step) {
                0 -> ServerStep(state, vm::onServerInput, vm::setAdvanced)
                1 -> AuthStep(state, session, vm)
                2 -> LauncherStep(state.launcher, vm::setLauncher)
                3 -> StorageStep(state, vm)
                else -> SummaryStep(state, vm)
            }
        }
        // Console convention: the confirm button sits bottom-right and carries its glyph, so the
        // user reads "press A to continue" instead of hunting for it with the d-pad. It also
        // takes focus on every step that needs no typing, which is why a long list never has to
        // be scrolled through just to reach "Next".
        // Extra room on the sides: the focus ring is drawn outside the button, and at the screen
        // edge the window clipped its right half.
        Row(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = layout.padding + 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (state.step > 0) {
                TextButton(
                    onClick = { vm.goTo(state.step - 1) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 38.dp).gamepadFocusRing(PillShape),
                ) {
                    if (hasGamepad) { PadGlyph(PadButton.B); Spacer(Modifier.width(6.dp)) }
                    Text(stringResource(R.string.action_back))
                }
                Spacer(Modifier.width(8.dp))
            }
            val nextFr = remember { FocusRequester() }
            LaunchedEffect(canNext, state.step) {
                if (canNext && state.step != 0) {
                    delay(120)
                    runCatching { nextFr.requestFocus() }
                }
            }
            Button(
                onClick = {
                    when (state.step) {
                        0 -> vm.confirmServer { vm.goTo(1) }
                        4 -> vm.finish(onDone)
                        else -> vm.goTo(state.step + 1)
                    }
                },
                enabled = canNext,
                // Trimmed from the Material default: at this size the pill is still an easy target
                // and it leaves room for the pop when the pad lands on it.
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 38.dp).focusRequester(nextFr).gamepadFocusRing(PillShape),
            ) {
                Text(stringResource(if (state.step == 4) R.string.action_finish else R.string.action_next))
                if (hasGamepad) { Spacer(Modifier.width(6.dp)); PadGlyph(PadButton.A) }
            }
        }
    }
}

/** Re-authentication after an expired session: the auth step alone. */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val vm: OnboardingViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    LaunchedEffect(session) { if (session is Session.LoggedIn) onDone() }
    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.ob_step_auth), subtitle = stringResource(R.string.login_expired))
        Box(Modifier.weight(1f)) { AuthStep(state, session, vm) }
    }
}

/* ---------------------------------- step 1 ---------------------------------- */

@Composable
private fun ServerStep(
    state: OnboardingState,
    onInput: (String) -> Unit,
    onAdvanced: (String, String, Boolean) -> Unit,
) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val fr = remember { FocusRequester() }
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(layout.padding * 2)) {
        Text(stringResource(R.string.ob_server_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.serverInput,
            onValueChange = onInput,
            singleLine = true,
            label = { Text(stringResource(R.string.ob_server_label)) },
            placeholder = { Text("http://192.168.50.11:8080") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).focusRequester(fr).gamepadTextField(MaterialTheme.shapes.small),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (val p = state.probe) {
                ProbeState.Idle -> Text(stringResource(R.string.ob_server_idle), color = colors.onSurfaceMuted)
                ProbeState.Probing -> { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ob_server_probing), color = colors.onSurfaceMuted) }
                is ProbeState.Ok -> { Icon(Icons.Rounded.CheckCircle, null, tint = colors.green); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ob_server_ok, p.version ?: "?", p.url)) }
                is ProbeState.Error -> { Icon(Icons.Rounded.Error, null, tint = colors.red); Spacer(Modifier.width(8.dp)); Text(p.message, color = colors.red) }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { advanced = true }, modifier = Modifier.gamepadFocusRing(PillShape)) {
            Text(stringResource(R.string.ob_server_advanced))
        }
        if (state.cfClientId.isNotBlank() || state.allowInsecureRemote) {
            Text(
                listOfNotNull(
                    state.cfClientId.takeIf { it.isNotBlank() }?.let { "Cloudflare Access" },
                    stringResource(R.string.settings_allow_insecure).takeIf { state.allowInsecureRemote },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
    }
    if (advanced) {
        AdvancedServerDialog(state, onDismiss = { advanced = false }) { id, secret, insecure ->
            onAdvanced(id, secret, insecure); advanced = false
        }
    }
}

/** Cloudflare Access credentials and the plain-HTTP override, available before login. */
@Composable
private fun AdvancedServerDialog(
    state: OnboardingState,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean) -> Unit,
) {
    var id by remember { mutableStateOf(state.cfClientId) }
    var secret by remember { mutableStateOf(state.cfClientSecret) }
    var insecure by remember { mutableStateOf(state.allowInsecureRemote) }
    RommDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ob_server_advanced),
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onConfirm(id, secret, insecure) },
        focusConfirm = false,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.settings_cf_hint), style = MaterialTheme.typography.bodySmall, color = RommTheme.colors.onSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(id, { id = it }, singleLine = true, label = { Text("CF-Access-Client-Id") }, modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(secret, { secret = it }, singleLine = true, label = { Text("CF-Access-Client-Secret") }, modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small))
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = stringResource(R.string.settings_allow_insecure),
                checked = insecure,
                onCheckedChange = { insecure = it },
                subtitle = stringResource(R.string.settings_allow_insecure_hint),
            )
        }
    }
}

/* ---------------------------------- step 2 ---------------------------------- */

@Composable
private fun AuthStep(state: OnboardingState, session: Session, vm: OnboardingViewModel) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val context = LocalContext.current
    // The device flow is the default method, so nothing triggers setAuthMethod for it: start it
    // when the step is first shown, otherwise the panel sits on a spinner forever.
    LaunchedEffect(state.authMethod, session) {
        if (state.authMethod == AuthMethod.DEVICE && session !is Session.LoggedIn && state.device.init == null && state.device.error == null) {
            vm.restartDeviceFlow()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = layout.padding)) {
        if (session is Session.LoggedIn) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = colors.green); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ob_auth_logged_in, session.username ?: ""), style = MaterialTheme.typography.bodyLarge)
            }
        }
        // Each method says what it does right under the tabs: "Token" and "Password" mean
        // nothing on their own, and the user should not have to try one to find out.
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AuthMethod.entries.forEach { m ->
                val selected = m == state.authMethod
                Text(
                    stringResource(authMethodLabel(m)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.white else colors.onSurface,
                    modifier = Modifier.gamepadFocusRing(PillShape).clip(PillShape).background(if (selected) colors.primary else colors.surface).clickable { vm.setAuthMethod(m) }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (session !is Session.LoggedIn) {
            Text(
                stringResource(authMethodDesc(state.authMethod)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val busy = state.auth == AuthUiState.Busy
        (state.auth as? AuthUiState.Error)?.let { Text(it.message, color = colors.red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Once signed in, the pairing panel would keep saying "waiting for approval".
            if (session is Session.LoggedIn) {
                Text(
                    stringResource(R.string.ob_auth_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else when (state.authMethod) {
                AuthMethod.DEVICE -> DeviceMethod(state.device, vm)
                AuthMethod.PASSWORD -> PasswordMethod(busy, vm)
                AuthMethod.TOKEN -> TokenMethod(busy, vm)
            }
        }
    }
}

private fun authMethodLabel(m: AuthMethod): Int = when (m) {
    AuthMethod.DEVICE -> R.string.auth_device
    AuthMethod.PASSWORD -> R.string.auth_password
    AuthMethod.TOKEN -> R.string.auth_token
}

private fun authMethodDesc(m: AuthMethod): Int = when (m) {
    AuthMethod.DEVICE -> R.string.auth_device_desc
    AuthMethod.PASSWORD -> R.string.auth_password_desc
    AuthMethod.TOKEN -> R.string.auth_token_desc
}


@Composable
private fun DeviceMethod(d: DeviceFlowState, vm: OnboardingViewModel) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        val init = d.init
        when {
            d.error != null -> {
                Text(d.error, color = colors.red, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.restartDeviceFlow() }, modifier = Modifier.gamepadFocusRing(PillShape)) { Text(stringResource(R.string.action_retry)) }
            }
            init == null -> CircularProgressIndicator()
            else -> {
                // One thing to do: point a phone at the code. The link already carries the
                // pairing code, so there is nothing left to type or to copy across.
                val qr = @Composable { QrImage(d.verificationUrl, Modifier.size(if (layout.isShort) 160.dp else 220.dp)) }
                val caption = @Composable {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            d.verificationUrl.removePrefix("http://").removePrefix("https://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceMuted,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.auth_device_waiting, d.secondsLeft / 60, d.secondsLeft % 60), color = colors.onSurfaceMuted)
                    }
                }
                // A short screen has no room for a stacked QR plus caption.
                if (layout.isShort) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        qr()
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.widthIn(max = 420.dp)) { caption() }
                    }
                } else {
                    qr()
                    Spacer(Modifier.height(10.dp))
                    caption()
                }
            }
        }
    }
}

@Composable
private fun PasswordMethod(busy: Boolean, vm: OnboardingViewModel) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).widthIn(max = 520.dp)) {
        OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.auth_username)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().focusRequester(fr).gamepadTextField(MaterialTheme.shapes.small))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.auth_password_field)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.loginPassword(user, pass) }, enabled = !busy && user.isNotBlank() && pass.isNotEmpty(), modifier = Modifier.gamepadFocusRing(PillShape)) { Text(stringResource(R.string.action_login)) }
    }
}

@Composable
private fun TokenMethod(busy: Boolean, vm: OnboardingViewModel) {
    var token by rememberSaveable { mutableStateOf("") }
    val colors = RommTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).widthIn(max = 520.dp)) {
        Text(stringResource(R.string.auth_token_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("rmm_…") }, modifier = Modifier.fillMaxWidth().gamepadTextField(MaterialTheme.shapes.small))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.loginToken(token) }, enabled = !busy && token.isNotBlank(), modifier = Modifier.gamepadFocusRing(PillShape)) { Text(stringResource(R.string.action_login)) }
    }
}

/* ---------------------------------- step 3 ---------------------------------- */

@Composable
private fun LauncherStep(selected: Launcher, onSelect: (Launcher) -> Unit) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / 200.dp).toInt().coerceIn(1, 4)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(layout.padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Launcher.entries, key = { it.id }) { l ->
                val isSel = l == selected
                val shape = MaterialTheme.shapes.large
                Column(
                    Modifier
                        .gamepadFocus(shape, scale = 1.03f)
                        .clip(shape)
                        .background(if (isSel) colors.primary.copy(alpha = 0.25f) else colors.surface)
                        .clickable { onSelect(l) }
                        .padding(12.dp)
                        .heightIn(min = 76.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(launcherLabel(l), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (isSel) Icon(Icons.Rounded.CheckCircle, null, tint = colors.primaryLighten)
                    }
                    Text(stringResource(launcherDescRes(l)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/* ---------------------------------- step 4 ---------------------------------- */

@Composable
private fun StorageStep(state: OnboardingState, vm: OnboardingViewModel) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val context = LocalContext.current
    var chooser by remember { mutableStateOf(false) }
    // Order matters here: access first, folder second. Without All files access the picked folder
    // silently becomes a SAF tree that every write has to go through, so the choice must not be
    // offered before the permission has been dealt with.
    var allFiles by remember { mutableStateOf(StorageLocations.hasAllFilesAccess(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val grantIntent = remember { StorageLocations.allFilesAccessIntent(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = StorageLocations.hasAllFilesAccess(context)
                if (now != allFiles) {
                    allFiles = now
                    // Same folder, new answer: what was read-only a moment ago is writable now.
                    vm.recheckRoot()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { askedOnce = true }
    // The folder step is reachable once access is granted, or when this device has no screen to
    // grant it on, or after the user has been to that screen once and come back without it: the
    // sequence is enforced, but nobody is left with no way forward.
    val canChoose = allFiles || grantIntent == null || askedOnce
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(layout.padding * 2)) {
        Text(stringResource(R.string.ob_storage_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(if (allFiles) R.string.ob_storage_allfiles_ok else R.string.ob_storage_allfiles_missing), style = MaterialTheme.typography.bodyMedium, color = if (allFiles) colors.green else colors.accent)
        Spacer(Modifier.height(16.dp))
        val root = state.root
        if (root != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, null, tint = colors.primaryLighten); Spacer(Modifier.width(8.dp))
                Column {
                    Text(root.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (state.rootWritable) { null -> stringResource(R.string.ob_storage_checking); true -> stringResource(R.string.ob_storage_writable); false -> stringResource(R.string.ob_storage_not_writable) },
                        style = MaterialTheme.typography.bodySmall, color = if (state.rootWritable == false) colors.red else colors.onSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val fr = remember { FocusRequester() }
        LaunchedEffect(canChoose) { runCatching { fr.requestFocus() } }
        when {
            !canChoose -> {
                // Step one, and the only button on screen until it is done.
                Button(onClick = { settingsLauncher.launch(grantIntent!!) }, modifier = Modifier.focusRequester(fr).gamepadFocusRing(PillShape)) {
                    Icon(Icons.Rounded.FolderOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.root_allfiles_grant))
                }
            }
            root == null ->
                Button(onClick = { chooser = true }, modifier = Modifier.focusRequester(fr).gamepadFocusRing(PillShape)) { Text(stringResource(R.string.ob_storage_choose)) }
            else ->
                OutlinedButton(onClick = { chooser = true }, modifier = Modifier.focusRequester(fr).gamepadFocusRing(PillShape)) { Text(stringResource(R.string.ob_storage_change)) }
        }
        if (canChoose && !allFiles && grantIntent != null) {
            // Came back without granting: the folder is offered, the grant stays one press away as
            // the quieter option, whether a folder is already set or not.
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { settingsLauncher.launch(grantIntent) }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                Text(stringResource(R.string.root_allfiles_grant))
            }
        }
    }
    if (chooser) RootChooserDialog(preferredNames = vm.rootNames, onChosen = { vm.setRoot(it); chooser = false }, onDismiss = { chooser = false })
}

/* ---------------------------------- step 5 ---------------------------------- */

@Composable
private fun SummaryStep(state: OnboardingState, vm: OnboardingViewModel) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    var editing by remember { mutableStateOf<MappingRow?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        val found = state.summary.count { it.status == MappingStatus.FOUND }
        Text(
            if (state.summaryBusy) stringResource(R.string.ob_summary_scanning) else stringResource(R.string.ob_summary_body, found, state.summary.size),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = layout.padding * 2, vertical = 8.dp),
        )
        if (state.summaryBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().rightStickScroll(listState), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
            items(state.summary, key = { it.platform.id }) { row ->
                val (icon, tint, text) = when (row.status) {
                    MappingStatus.FOUND -> Triple(Icons.Rounded.CheckCircle, colors.green, row.mapping!!.relDir)
                    MappingStatus.TO_CREATE -> Triple(Icons.Rounded.CreateNewFolder, colors.accent, stringResource(R.string.mappings_to_create, row.proposal ?: row.platform.slug))
                    MappingStatus.TO_CHOOSE -> Triple(Icons.Rounded.Edit, colors.blue, stringResource(R.string.mappings_to_choose, row.candidates.joinToString(", ")))
                    MappingStatus.UNKNOWN -> Triple(Icons.Rounded.Edit, colors.gray, stringResource(R.string.mappings_unknown))
                }
                SettingRow(title = row.platform.name, subtitle = text, onClick = { editing = row }) { Icon(icon, null, tint = tint) }
            }
        }
    }
    editing?.let { row ->
        MappingEditDialog(
            row = row,
            rootDirs = state.rootDirs,
            onChoose = { vm.summaryChoose(row.platform.slug, it); editing = null },
            onCreate = { vm.summaryCreate(row.platform.slug, it); editing = null },
            onClear = { vm.summaryClear(row.platform.slug); editing = null },
            onDismiss = { editing = null },
        )
    }
}
