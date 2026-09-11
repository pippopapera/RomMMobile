package com.rommmobile.app.feature.controls

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PadButton
import com.rommmobile.app.core.design.PadGlyph
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.input.ButtonMap
import com.rommmobile.app.core.input.LocalGamepad
import com.rommmobile.app.core.input.LogicalButton
import kotlinx.coroutines.delay

/** The printed name of a button, in the user's language for the directions. */
fun LogicalButton.label(context: Context): String = when (this) {
    LogicalButton.UP -> context.getString(R.string.btn_up)
    LogicalButton.DOWN -> context.getString(R.string.btn_down)
    LogicalButton.LEFT -> context.getString(R.string.btn_left)
    LogicalButton.RIGHT -> context.getString(R.string.btn_right)
    else -> name
}

@Composable
fun LogicalButton.label(): String = label(LocalContext.current)

/** What the recorder just did, shown for a moment under the next prompt. */
sealed interface CaptureFeedback {
    data class Assigned(val button: LogicalButton, val code: Int) : CaptureFeedback
    data class Swapped(val button: LogicalButton, val other: LogicalButton) : CaptureFeedback
    data class Refused(val code: Int, val owner: LogicalButton) : CaptureFeedback
    /** The key belongs to a required button that would be left with nothing. */
    data class RefusedRequired(val owner: LogicalButton) : CaptureFeedback
    /** The other button gave its key away and got nothing back. */
    data class Cleared(val owner: LogicalButton) : CaptureFeedback
    data class Unusable(val code: Int) : CaptureFeedback
    data class Undone(val button: LogicalButton) : CaptureFeedback
    data class Skipped(val button: LogicalButton) : CaptureFeedback
}

/**
 * One recording pass over [order]. Fed raw key events by the activity while its card is mounted;
 * every other consumer is cut off for that time, so pressing B here names B instead of leaving.
 *
 * A press is a DOWN followed by its UP, both seen by the same prompt: the UP alone (the tail of
 * the press that opened the card) assigns nothing. A hold of about a second undoes the last
 * assignment instead, a hold of two seconds leaves the recorder ([onQuit]): with every key
 * being recorded, holding is the only gesture a pad has left. Both are measured on the event's
 * own timestamps, since auto-repeat is not delivered for every pad.
 */
class CaptureSession(
    val order: List<LogicalButton>,
    start: ButtonMap,
    /** True in the guided pass: a code already given to an earlier prompt is refused, not swapped. */
    private val refuseDuplicates: Boolean,
) {
    var index by mutableIntStateOf(0)
        private set
    var map by mutableStateOf(start)
        private set
    var feedback by mutableStateOf<CaptureFeedback?>(null)
    var done by mutableStateOf(false)
        private set
    private val assignedHere = LinkedHashSet<LogicalButton>()
    private var downCode: Int? = null
    /** Set by the card: what a two-second hold does (leave, keeping the previous map). */
    var onQuit: (() -> Unit)? = null

    val current: LogicalButton? get() = order.getOrNull(index)
    val isGuided: Boolean get() = order.size > 1
    val canSkip: Boolean get() = isGuided && current?.required == false

    fun onKey(event: KeyEvent) {
        if (done) return
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                if (!acceptable(event)) { feedback = CaptureFeedback.Unusable(code); downCode = null; return }
                downCode = code
            }
            KeyEvent.ACTION_UP -> {
                if (downCode != code) return
                downCode = null
                val held = event.eventTime - event.downTime
                when {
                    held >= QUIT_MS -> onQuit?.invoke()
                    // With nothing to undo a long press is just a press: the first prompt, or
                    // any single-button recording, must not eat a deliberate slow press.
                    held >= HOLD_MS && index > 0 -> undoLast()
                    else -> assign(code)
                }
            }
        }
    }

    /**
     * Only pad keys: a key from a keyboard or the volume rocker, or one of the sticks (which keep
     * their own jobs), must not end up bound to a button. The platform's synthetic d-pad keys
     * (a hat reported as DPAD_*) are welcome only where a direction is being asked for.
     */
    private fun acceptable(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBL || code == KeyEvent.KEYCODE_BUTTON_THUMBR) return false
        if (event.flags and KeyEvent.FLAG_FALLBACK != 0 && current?.isDirection != true) return false
        val sources = event.device?.sources ?: 0
        val padSource = sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) != 0
        return padSource || KeyEvent.isGamepadButton(code) || isDpadCode(code)
    }

    private fun isDpadCode(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun assign(code: Int) {
        val button = current ?: return
        val owner = map.logicalFor(code)
        val hadCode = map.codeFor(button) != null
        if (owner != null && owner != button) {
            if (refuseDuplicates && owner in assignedHere) { feedback = CaptureFeedback.Refused(code, owner); return }
            // A swap that would leave a required button with nothing is not a swap.
            if (!refuseDuplicates && owner.required && !hadCode) { feedback = CaptureFeedback.RefusedRequired(owner); return }
        }
        map = map.with(button, code)
        assignedHere += button
        feedback = when {
            owner == null || owner == button || refuseDuplicates -> CaptureFeedback.Assigned(button, code)
            hadCode -> CaptureFeedback.Swapped(button, owner)
            else -> CaptureFeedback.Cleared(owner)
        }
        advance()
    }

    /** Leaves the current button unbound; a pad that lacks it has no key to give. */
    fun skip() {
        val button = current ?: return
        if (button.required) return
        map = map.without(button)
        assignedHere -= button
        feedback = CaptureFeedback.Skipped(button)
        advance()
    }

    fun undoLast() {
        if (index == 0) return
        index -= 1
        val button = order[index]
        map = map.without(button)
        assignedHere -= button
        downCode = null
        feedback = CaptureFeedback.Undone(button)
    }

    private fun advance() {
        downCode = null
        index += 1
        if (index >= order.size) done = true
    }

    companion object {
        const val HOLD_MS = 700L
        const val QUIT_MS = 2000L
        val FULL_ORDER: List<LogicalButton> = LogicalButton.entries
    }
}

/**
 * The recording card, drawn INSIDE the screen rather than in a dialog window on purpose: a
 * dialog has its own window and the activity's key dispatch, which is where the recorder is fed,
 * would never see the keys. Its own buttons are for fingers: while it is up the pad is the thing
 * being described, and focus is kept off them so it stays where the screen left it.
 */
@Composable
fun ButtonCaptureOverlay(
    session: CaptureSession,
    onFinished: (ButtonMap) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bus = LocalGamepad.current
    val colors = RommTheme.colors
    val finished by rememberUpdatedState(onFinished)
    val cancel by rememberUpdatedState(onCancel)
    DisposableEffect(session, bus) {
        val sink: (KeyEvent) -> Unit = { e -> session.onKey(e) }
        session.onQuit = { cancel() }
        bus.beginCapture(sink)
        onDispose { bus.endCapture(sink); session.onQuit = null }
    }
    // The system's back (gesture or nav bar) is the one key the recorder lets through: it leaves.
    BackHandler { cancel() }
    LaunchedEffect(session.done) { if (session.done) finished(session.map.completed()) }
    LaunchedEffect(session.feedback) { if (session.feedback != null) { delay(1800); session.feedback = null } }
    // Nothing moves on by itself: a pause while reading must not become a skipped button, let
    // alone a completed sequence written to disk behind the user's back. The way out is a
    // two-second hold, the Quit button, or the system's back.

    Box(
        modifier.fillMaxSize()
            // The scrim owns every touch: the table or wizard underneath must not be reachable
            // while its map is being rewritten, or a tap there would be undone by the result.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = colors.toplayer, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 2.dp) {
            Column(
                Modifier.widthIn(min = 280.dp, max = 440.dp).padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val current = session.current
                if (current != null) {
                    if (session.isGuided) {
                        Text(
                            stringResource(R.string.controls_progress, session.index + 1, session.order.size),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        if (current.isDirection) stringResource(R.string.controls_press, current.label()) else stringResource(R.string.controls_press_marked, current.label()),
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    ButtonFace(current, size = 56.dp)
                    Spacer(Modifier.height(14.dp))
                }
                // The line under the prompt: what the last press did, or how to take it back.
                val fb = session.feedback
                Text(
                    when (fb) {
                        is CaptureFeedback.Assigned -> stringResource(R.string.controls_assigned, fb.button.label(), ButtonMap.describe(fb.code))
                        is CaptureFeedback.Swapped -> stringResource(R.string.controls_swapped, fb.button.label(), fb.other.label())
                        is CaptureFeedback.Refused -> stringResource(R.string.controls_refused, ButtonMap.describe(fb.code), fb.owner.label())
                        is CaptureFeedback.RefusedRequired -> stringResource(R.string.controls_refused_required, fb.owner.label())
                        is CaptureFeedback.Cleared -> stringResource(R.string.controls_cleared, fb.owner.label())
                        is CaptureFeedback.Unusable -> stringResource(R.string.controls_unusable, ButtonMap.describe(fb.code))
                        is CaptureFeedback.Undone -> stringResource(R.string.controls_undone, fb.button.label())
                        is CaptureFeedback.Skipped -> stringResource(R.string.controls_skipped, fb.button.label())
                        null -> stringResource(R.string.controls_hold_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (fb) {
                        is CaptureFeedback.Assigned, is CaptureFeedback.Swapped -> colors.green
                        is CaptureFeedback.Refused, is CaptureFeedback.RefusedRequired, is CaptureFeedback.Unusable -> colors.red
                        is CaptureFeedback.Undone, is CaptureFeedback.Skipped, is CaptureFeedback.Cleared -> colors.accent
                        null -> colors.onSurfaceMuted
                    },
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val touchOnly = Modifier.focusProperties { canFocus = false }
                    TextButton(onClick = { session.undoLast() }, enabled = session.index > 0, modifier = touchOnly) { Text(stringResource(R.string.controls_undo_last)) }
                    if (session.canSkip) TextButton(onClick = { session.skip() }, modifier = touchOnly) { Text(stringResource(R.string.controls_skip)) }
                    TextButton(onClick = { cancel() }, modifier = touchOnly) { Text(stringResource(R.string.controls_quit)) }
                }
            }
        }
    }
}

/** The button as drawn on the pad: its sprite when it has one, an arrow or its name otherwise. */
@Composable
fun ButtonFace(button: LogicalButton, size: Dp, modifier: Modifier = Modifier) {
    val colors = RommTheme.colors
    val pad = PadButton.of(button)
    if (pad != null && pad.sprite != null) {
        PadGlyph(pad, size = size, modifier = modifier)
        return
    }
    val arrow = when (button) {
        LogicalButton.UP -> Icons.Rounded.ArrowUpward
        LogicalButton.DOWN -> Icons.Rounded.ArrowDownward
        LogicalButton.LEFT -> Icons.Rounded.ArrowBack
        LogicalButton.RIGHT -> Icons.Rounded.ArrowForward
        else -> null
    }
    Box(modifier.size(size).background(colors.gray, CircleShape), contentAlignment = Alignment.Center) {
        if (arrow != null) Icon(arrow, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
        else Text(button.name, fontWeight = FontWeight.Bold, color = Color.White, style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall)
    }
}

/** A row's worth of "button = key": name and key, nothing decorative. */
@Composable
fun MappingRow(button: LogicalButton, map: ButtonMap, modifier: Modifier = Modifier) {
    val colors = RommTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(button.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(8.dp))
        val code = map.codeFor(button)
        Text(
            if (code != null) ButtonMap.describe(code) else stringResource(R.string.controls_not_assigned),
            style = MaterialTheme.typography.bodyMedium,
            color = if (code != null) colors.onSurfaceMuted else colors.accent,
        )
    }
}
