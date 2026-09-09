package com.rommmobile.app.core.design

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
 * The face that fires each action. Mirrors GamepadBus.mapKey and is the ONLY place a hint can
 * get its glyph, so a screen can no longer print "≡ Sort" while ≡ is wired to the context menu.
 */
val GamepadAction.button: PadButton
    get() = when (this) {
        GamepadAction.DOWNLOAD -> PadButton.X
        GamepadAction.TOGGLE_VIEW -> PadButton.Y
        GamepadAction.PREV_SECTION -> PadButton.L1
        GamepadAction.NEXT_SECTION -> PadButton.R1
        GamepadAction.OPEN_SEARCH -> PadButton.L3
        GamepadAction.OPEN_DOWNLOADS -> PadButton.R3
        // Deliberately crossed. The handhelds this targets report their physical Start as
        // KEYCODE_BUTTON_SELECT and vice versa, so the button a user presses for "sort" is the one
        // printed Start on the shell. The bar names what is under their thumb, not what the
        // keycode is called.
        GamepadAction.CONTEXT_MENU -> PadButton.SELECT
        GamepadAction.FILTERS -> PadButton.START
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
    val hints = map.rows.mapNotNull { row -> row.label?.let { row.action.button to stringResource(it) } }
    if (hasGamepad && hints.isNotEmpty()) KeyHintBar(hints, modifier)
}
