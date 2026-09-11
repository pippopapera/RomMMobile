package com.rommmobile.app.core.design

import com.rommmobile.app.core.input.LogicalButton
import com.rommmobile.app.core.input.LocalGamepad
import androidx.compose.runtime.collectAsState
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rommmobile.app.core.input.GamepadAction
import com.rommmobile.app.core.input.GamepadActions
import com.rommmobile.app.core.input.rememberHasGamepad

/**
 * The face that fires each action. Mirrors GamepadBus.actionFor and is the ONLY place a hint can
 * get its glyph, so a screen can no longer print "≡ Sort" while ≡ is wired to the context menu.
 * In printed buttons: which physical key is "Start" is the ButtonMap's business.
 */
val GamepadAction.button: PadButton
    get() = when (this) {
        GamepadAction.DOWNLOAD -> PadButton.X
        GamepadAction.TOGGLE_VIEW -> PadButton.Y
        GamepadAction.PREV_SECTION -> PadButton.L1
        GamepadAction.NEXT_SECTION -> PadButton.R1
        GamepadAction.OPEN_SEARCH -> PadButton.L3
        GamepadAction.OPEN_DOWNLOADS -> PadButton.R3
        GamepadAction.CONTEXT_MENU -> PadButton.SELECT
        GamepadAction.FILTERS -> PadButton.START
    }

/** The printed button behind an action, null for the sticks (raw keys, never in the map). */
val GamepadAction.logical: LogicalButton?
    get() = when (this) {
        GamepadAction.DOWNLOAD -> LogicalButton.X
        GamepadAction.TOGGLE_VIEW -> LogicalButton.Y
        GamepadAction.PREV_SECTION -> LogicalButton.L1
        GamepadAction.NEXT_SECTION -> LogicalButton.R1
        GamepadAction.CONTEXT_MENU -> LogicalButton.SELECT
        GamepadAction.FILTERS -> LogicalButton.START
        GamepadAction.OPEN_SEARCH, GamepadAction.OPEN_DOWNLOADS -> null
    }

/** One screen's whole gamepad contract: the same rows are what it runs and what it shows. */
@Stable
class PadMap internal constructor(internal val rows: List<Row>) {
    internal class Row(val action: GamepadAction, @StringRes val label: Int?, val run: () -> Unit)

    internal fun dispatch(action: GamepadAction): Boolean {
        val row = rows.firstOrNull { it.action == action } ?: return false
        row.run()
        return true
    }
}

class PadMapBuilder internal constructor() {
    private val rows = mutableListOf<PadMap.Row>()

    /** Handled AND advertised: the normal case. The bar can only show what the screen runs. */
    fun bind(action: GamepadAction, @StringRes label: Int, run: () -> Unit) {
        rows += PadMap.Row(action, label, run)
    }

    /**
     * Handled on purpose WITHOUT a hint. Only for shortcuts the screen already explains some
     * other way: the rail highlights the section L1/R1 move between, the queue bar is where R3
     * lands. A separate verb keeps a silent binding a decision instead of an oversight.
     */
    fun silent(action: GamepadAction, run: () -> Unit) {
        rows += PadMap.Row(action, null, run)
    }

    internal fun toMap(): PadMap {
        require(rows.distinctBy { it.action }.size == rows.size) {
            "the same action is bound twice: ${rows.map { it.action }}"
        }
        return PadMap(rows)
    }
}

/**
 * Declares this screen's gamepad contract once and draws its hint bar from the same rows.
 * Put it where the bar belongs (last child of the screen's Column); it draws nothing when every
 * row is silent, or when no pad is attached, but the handling is installed either way.
 *
 * A and B are never listed: "A opens what is focused" and "B goes back" are the platform's own
 * contract, already carried by the focus ring and the top bar's arrow.
 */
@Composable
fun PadBar(vararg keys: Any?, modifier: Modifier = Modifier, content: PadMapBuilder.() -> Unit) {
    val map = PadMapBuilder().apply(content).toMap()
    GamepadActions(*keys) { action -> map.dispatch(action) }
    val hasGamepad by rememberHasGamepad()
    // A button the user left unbound cannot fire, so its hint would be a lie.
    val buttons by LocalGamepad.current.mapFlow.collectAsState()
    val hints = map.rows.mapNotNull { row ->
        val logical = row.action.logical
        if (logical != null && buttons.codeFor(logical) == null) return@mapNotNull null
        row.label?.let { row.action.button to stringResource(it) }
    }
    if (hasGamepad && hints.isNotEmpty()) KeyHintBar(hints, modifier)
}
