package com.rommmobile.app.core.input

import android.view.KeyEvent

/**
 * The buttons this app talks about, as printed on the shell. What Android calls them varies by
 * handheld (the Retroid Pocket Classic reports the button printed Start as KEYCODE_BUTTON_SELECT
 * and vice versa; the Nova differs again), so every physical key goes through a [ButtonMap] first
 * and the rest of the app only ever sees these.
 *
 * [required] buttons can never be left unbound: without A nothing can be activated, without B
 * nothing can be left, and the wizard that could fix it would itself be out of reach.
 */
enum class LogicalButton(val canonical: Int, val required: Boolean) {
    UP(KeyEvent.KEYCODE_DPAD_UP, true),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN, true),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT, true),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT, true),
    A(KeyEvent.KEYCODE_BUTTON_A, true),
    B(KeyEvent.KEYCODE_BUTTON_B, true),
    X(KeyEvent.KEYCODE_BUTTON_X, false),
    Y(KeyEvent.KEYCODE_BUTTON_Y, false),
    START(KeyEvent.KEYCODE_BUTTON_START, false),
    SELECT(KeyEvent.KEYCODE_BUTTON_SELECT, false),
    L1(KeyEvent.KEYCODE_BUTTON_L1, false),
    R1(KeyEvent.KEYCODE_BUTTON_R1, false),
    L2(KeyEvent.KEYCODE_BUTTON_L2, false),
    R2(KeyEvent.KEYCODE_BUTTON_R2, false);

    val isDirection: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

/**
 * Physical keycode per printed button. Immutable; every change returns a new map.
 *
 * Serialised as `UP:19,DOWN:20,...,L2:-1` with EVERY button present, `-1` meaning deliberately
 * unbound, so a skipped trigger stays skipped across launches while a button this build knows
 * and an older string does not falls back to its default. A missing preference is [DEFAULT].
 */
class ButtonMap private constructor(private val codes: Map<LogicalButton, Int>) {

    fun codeFor(button: LogicalButton): Int? = codes[button]

    fun logicalFor(code: Int): LogicalButton? = codes.entries.firstOrNull { it.value == code }?.key

    val isDefault: Boolean get() = codes == DEFAULT.codes

    /** Every required button has a key: the only kind of map that may be persisted. */
    val isUsable: Boolean get() = LogicalButton.entries.all { !it.required || codes.containsKey(it) }

    /**
     * Binds [button] to [code]. A code already held by another button is not duplicated: that
     * button takes the code [button] used to have (a plain swap, which is what "my A and B are
     * the other way round" needs), or nothing if [button] had none.
     */
    fun with(button: LogicalButton, code: Int): ButtonMap {
        val next = codes.toMutableMap()
        val previousOwner = logicalFor(code)
        val previousCode = next[button]
        if (previousOwner != null && previousOwner != button) {
            if (previousCode != null) next[previousOwner] = previousCode else next.remove(previousOwner)
        }
        next[button] = code
        return ButtonMap(next)
    }

    fun without(button: LogicalButton): ButtonMap = ButtonMap(codes - button)

    /** Required buttons left unbound take their default key, so the result is always usable. */
    fun completed(): ButtonMap {
        if (isUsable) return this
        val next = codes.toMutableMap()
        for (b in LogicalButton.entries) if (b.required && b !in next) next[b] = DEFAULT.codes.getValue(b)
        return ButtonMap(next)
    }

    fun encode(): String = LogicalButton.entries.joinToString(",") { "${it.name}:${codes[it] ?: UNBOUND}" }

    override fun equals(other: Any?): Boolean = other is ButtonMap && other.codes == codes
    override fun hashCode(): Int = codes.hashCode()
    override fun toString(): String = "ButtonMap(${encode()})"

    companion object {
        private const val UNBOUND = -1

        /**
         * What the app assumed before it could be told otherwise, kept as the default so an
         * upgrade changes nothing: Start and Select are crossed, the way the Retroid Pocket
         * Classic reports them. A fresh install replaces this with what the wizard records.
         */
        val DEFAULT: ButtonMap = ButtonMap(
            mapOf(
                LogicalButton.UP to KeyEvent.KEYCODE_DPAD_UP,
                LogicalButton.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                LogicalButton.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
                LogicalButton.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
                LogicalButton.A to KeyEvent.KEYCODE_BUTTON_A,
                LogicalButton.B to KeyEvent.KEYCODE_BUTTON_B,
                LogicalButton.X to KeyEvent.KEYCODE_BUTTON_X,
                LogicalButton.Y to KeyEvent.KEYCODE_BUTTON_Y,
                LogicalButton.START to KeyEvent.KEYCODE_BUTTON_SELECT,
                LogicalButton.SELECT to KeyEvent.KEYCODE_BUTTON_START,
                LogicalButton.L1 to KeyEvent.KEYCODE_BUTTON_L1,
                LogicalButton.R1 to KeyEvent.KEYCODE_BUTTON_R1,
                LogicalButton.L2 to KeyEvent.KEYCODE_BUTTON_L2,
                LogicalButton.R2 to KeyEvent.KEYCODE_BUTTON_R2,
            )
        )

        /** Nothing bound: the wizard's starting point, filled one button at a time. */
        val EMPTY: ButtonMap = ButtonMap(emptyMap())

        /**
         * A missing string is the default. A present one is authoritative for every button it
         * names (including `-1`, unbound); a garbled entry is skipped and a button it does not
         * name takes its default, which is how a string from an older build stays valid.
         */
        fun decode(s: String?): ButtonMap {
            if (s.isNullOrBlank()) return DEFAULT
            val named = HashMap<LogicalButton, Int>()
            for (part in s.split(',')) {
                val i = part.indexOf(':')
                if (i <= 0) continue
                val button = LogicalButton.entries.firstOrNull { it.name == part.substring(0, i).trim() } ?: continue
                val code = part.substring(i + 1).trim().toIntOrNull() ?: continue
                named[button] = code
            }
            val out = HashMap<LogicalButton, Int>()
            for (b in LogicalButton.entries) {
                val code = named[b]
                when {
                    code == null -> DEFAULT.codes[b]?.let { out[b] = it }
                    code == UNBOUND -> Unit
                    else -> out[b] = code
                }
            }
            return ButtonMap(out)
        }

        /** "BUTTON_A (96)": the name Android gives the key, so the user can recognise it later. */
        fun describe(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_") + " ($code)"
    }
}
