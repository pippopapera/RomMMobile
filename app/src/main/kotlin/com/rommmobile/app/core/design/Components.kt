package com.rommmobile.app.core.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.rommmobile.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rommmobile.app.core.input.LocalGamepad
import com.rommmobile.app.core.input.ModalScope
import kotlin.math.roundToInt

/* ----------------------------- top bar ----------------------------- */

@Composable
fun RommTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val layout = RommTheme.layout
    val colors = RommTheme.colors
    Surface(color = colors.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.height(layout.topBarHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.gamepadFocusIcon()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null && !layout.isShort) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/* ----------------------------- skeleton ----------------------------- */

@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.medium) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    Box(modifier.alpha(alpha).clip(shape).background(RommTheme.colors.toplayer))
}

/* ----------------------------- images ----------------------------- */

/**
 * An image request that asks again, twice and spaced out, when the load fails. Measured on the
 * owner's handheld: a 2.4 GHz link with a quarter of its packets retransmitted turns a few
 * covers per screen into timeouts, and a failed load used to stay a grey tile until the card
 * was composed again from scratch. The memory-cache key changes per attempt so Coil sees a new
 * request rather than an equal one it has already answered; the disk-cache key does not, so a
 * retry that succeeds is cached like any other load.
 */
@Composable
private fun rememberRetryingRequest(url: String, crossfadeMs: Int?): Pair<ImageRequest, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember(url) { mutableIntStateOf(0) }
    val request = remember(url, attempt) {
        ImageRequest.Builder(context).data(url)
            .apply { if (crossfadeMs != null) crossfade(crossfadeMs) }
            .apply { if (attempt > 0) memoryCacheKey("$url#retry$attempt") }
            .build()
    }
    val retry: () -> Unit = {
        if (attempt < 2) scope.launch { delay(1500L * (attempt + 1)); attempt++ }
    }
    return request to retry
}

@Composable
fun CoverImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val colors = RommTheme.colors
    val placeholder = remember(colors) { ColorPainter(colors.toplayer) }
    val fallback = rememberVectorPainter(Icons.Rounded.VideogameAsset)
    if (url == null) {
        Box(modifier.clip(shape).background(colors.toplayer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.VideogameAsset, contentDescription = contentDescription, tint = colors.onSurfaceMuted, modifier = Modifier.size(28.dp))
        }
        return
    }
    val (request, retry) = rememberRetryingRequest(url, crossfadeMs = 120)
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.clip(shape).background(colors.toplayer),
        placeholder = placeholder,
        error = fallback,
        fallback = fallback,
        contentScale = contentScale,
        onError = { retry() },
    )
}

@Composable
fun PlatformIcon(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val fallback = rememberVectorPainter(Icons.Rounded.VideogameAsset)
    if (url == null) {
        Icon(Icons.Rounded.VideogameAsset, contentDescription = null, modifier = modifier.size(size))
        return
    }
    val (request, retry) = rememberRetryingRequest(url, crossfadeMs = null)
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.size(size),
        error = fallback,
        fallback = fallback,
        contentScale = ContentScale.Fit,
        onError = { retry() },
    )
}

/* ----------------------------- small bits ----------------------------- */

@Composable
fun StatusPill(text: String, container: Color, content: Color = Color.White, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.background(container, PillShape).padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
    )
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, container: Color = RommTheme.colors.translucent) {
    Text(
        text = text,
        modifier = modifier.background(container, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = RommTheme.layout.padding, vertical = 6.dp),
    )
}

/* ----------------------------- list states ----------------------------- */

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = RommTheme.colors.onSurfaceMuted, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = RommTheme.colors.onSurfaceMuted, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            AutoFocusButton(actionLabel, onAction)
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = RommTheme.colors.red, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            AutoFocusButton(stringResource(R.string.action_retry), onRetry)
        }
    }
}

/** Button that takes focus when it appears: "Retry" must be one press away on a gamepad. */
@Composable
fun AutoFocusButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fr = remember { FocusRequester() }
    // Asking once fails silently while the node is still being placed; a few spaced requests
    // cost nothing and re-requesting on an already-focused node is a no-op.
    LaunchedEffect(Unit) { repeat(6) { runCatching { fr.requestFocus() }; delay(40) } }
    Button(onClick = onClick, modifier = modifier.focusRequester(fr).gamepadFocusRing(PillShape)) { Text(label) }
}

@Composable
fun OfflineBanner(visible: Boolean, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    AnimatedVisibility(visible, modifier = modifier, enter = expandVertically(), exit = shrinkVertically()) {
        // Tappable on purpose: the banner used to be the only place that said we were offline and
        // the only place with no way to say otherwise, so it stayed up for the whole session.
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onRetry != null) Modifier.gamepadFocusRow(RectangleShape).clickable(onClick = onRetry) else Modifier)
                .background(RommTheme.colors.toplayer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = RommTheme.colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onRetry != null) {
                Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelMedium, color = RommTheme.colors.primaryLighten)
            }
        }
    }
}

/* ----------------------------- settings rows ----------------------------- */

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = RommTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .gamepadFocusRow(MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    SettingRow(title = title, subtitle = subtitle, onClick = { onCheckedChange(!checked) }, enabled = enabled, modifier = modifier) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/* ----------------------------- dialogs ----------------------------- */

/**
 * Focus lands on the confirm button; B (BACK) dismisses. Global gamepad shortcuts are muted
 * while it is on screen thanks to [ModalScope].
 */
@Composable
fun RommDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = stringResource(R.string.action_cancel),
    destructive: Boolean = false,
    focusConfirm: Boolean = true,
    content: @Composable () -> Unit,
) {
    ModalScope()
    val confirmFr = remember { FocusRequester() }
    val dismissFr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Dialog content lives in its own window and may not be placed on the first frame.
        repeat(6) {
            runCatching { if (focusConfirm) confirmFr.requestFocus() else dismissFr.requestFocus() }
            delay(40)
        }
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = RommTheme.colors.toplayer,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = content,
        confirmButton = {
            if (destructive) {
                TextButton(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFr).gamepadFocusRing(PillShape)) {
                    Text(confirmText, color = RommTheme.colors.red)
                }
            } else {
                Button(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFr).gamepadFocusRing(PillShape)) { Text(confirmText) }
            }
        },
        dismissButton = if (dismissText != null) {
            { TextButton(onClick = onDismissRequest, modifier = Modifier.focusRequester(dismissFr).gamepadFocusRing(PillShape)) { Text(dismissText) } }
        } else null,
        properties = DialogProperties(usePlatformDefaultWidth = RommTheme.layout.widthDp >= 480),
    )
}

/* ----------------------------- key hints ----------------------------- */

/**
 * Face and menu buttons carry a pixel-art sprite from the pad pack (16 px cell, idle and pressed
 * faces, recoloured onto the app palette); the shoulder and stick buttons have no sprite and fall
 * back to a drawn disc with the label.
 */
enum class PadButton(val glyph: String, val sprite: Int? = null, val spritePressed: Int? = null) {
    A("A", R.drawable.pad_south, R.drawable.pad_south_p),
    B("B", R.drawable.pad_east, R.drawable.pad_east_p),
    X("X", R.drawable.pad_west, R.drawable.pad_west_p),
    Y("Y", R.drawable.pad_north, R.drawable.pad_north_p),
    L1("L1"), R1("R1"), L3("L3"), R3("R3"),
    START("≡", R.drawable.pad_start, R.drawable.pad_start_p),
    SELECT("⧉", R.drawable.pad_select, R.drawable.pad_select_p);

    companion object {
        /**
         * Which drawn button a physical key lights up. Start and Select are crossed on purpose,
         * the same way PadBar maps the actions: the handhelds this targets report the button
         * printed Start as KEYCODE_BUTTON_SELECT and vice versa.
         */
        fun forKeyCode(code: Int): PadButton? = when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> A
            KeyEvent.KEYCODE_BUTTON_B -> B
            KeyEvent.KEYCODE_BUTTON_X -> X
            KeyEvent.KEYCODE_BUTTON_Y -> Y
            KeyEvent.KEYCODE_BUTTON_L1 -> L1
            KeyEvent.KEYCODE_BUTTON_R1 -> R1
            KeyEvent.KEYCODE_BUTTON_THUMBL -> L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> R3
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> SELECT
            KeyEvent.KEYCODE_BUTTON_SELECT -> START
            else -> null
        }
    }
}

/** True while the physical key behind [button] is held down. */
@Composable
private fun isHeld(button: PadButton): Boolean {
    val held by LocalGamepad.current.held.collectAsState()
    return held.any { PadButton.forKeyCode(it) == button }
}

/**
 * The sprite, drawn pixel-exact: a whole multiple of the 16 px cell nearest to [size], sampled
 * nearest-neighbour, so the art never smears into a blurry disc at 2.6x density.
 */
@Composable
private fun PadSprite(button: PadButton, pressed: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val id = (if (pressed) button.spritePressed else button.sprite) ?: return
    val bitmap = ImageBitmap.imageResource(id)
    // Every sprite is sized off the SAME 48-or-so pixel target, chosen once from the 16 px face
    // cell, and then each file takes the whole multiple of its own cell nearest that target. The
    // face buttons fill their 16 px cell while the Start/Select arrows are 12 px discs (cropped
    // files), and rounding each from the requested dp independently split them: at the Retroid
    // density of 2.0, 20 dp is 40 px, which is 3x16 = 48 for a letter but 3x12 = 36 for an arrow.
    // Anchoring on the face size gives 48 / 12 = 4x, and the arrows land on the same 48 px.
    val density = LocalDensity.current
    val exact = with(density) {
        val faceScale = (size.toPx() / 16f).roundToInt().coerceAtLeast(1)
        val target = 16 * faceScale
        val scale = (target.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)
        (bitmap.width * scale).toDp()
    }
    // The bitmap overload: it is the one that accepts a filter quality, and None is the whole
    // point - the painter overload would resample the art with a smoothing filter.
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.size(exact),
        filterQuality = FilterQuality.None,
    )
}

/**
 * The bare button glyph, for placing inside a control ("Next (A)"). Consoles put the glyph on
 * the action itself so the mapping is readable without a separate legend.
 */
@Composable
fun PadGlyph(button: PadButton, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    if (button.sprite != null) {
        PadSprite(button, pressed = isHeld(button), size = size, modifier = modifier)
        return
    }
    val colors = RommTheme.colors
    val bg = when (button) {
        PadButton.A -> colors.green
        PadButton.B -> colors.red
        PadButton.X -> colors.blue
        PadButton.Y -> colors.gold
        else -> colors.gray
    }
    Box(modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            button.glyph,
            fontSize = (size.value * 0.6f).sp,
            lineHeight = (size.value * 0.6f).sp,
            fontWeight = FontWeight.Bold,
            color = if (button == PadButton.Y) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

@Composable
fun KeyHint(button: PadButton, label: String, modifier: Modifier = Modifier) {
    val colors = RommTheme.colors
    if (button.sprite != null) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            PadSprite(button, pressed = isHeld(button), size = 20.dp)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, maxLines = 1)
        }
        return
    }
    val bg = when (button) {
        PadButton.A -> colors.green
        PadButton.B -> colors.red
        PadButton.X -> colors.blue
        PadButton.Y -> colors.gold
        else -> colors.gray
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(if (button.glyph.length > 1) 22.dp else 18.dp).background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(button.glyph, fontSize = if (button.glyph.length > 1) 9.sp else 11.sp, fontWeight = FontWeight.Bold, color = if (button == PadButton.Y) Color.Black else Color.White, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, maxLines = 1)
    }
}

@Composable
fun KeyHintBar(hints: List<Pair<PadButton, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(RommTheme.colors.background).padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { (b, l) -> KeyHint(b, l) }
    }
}
