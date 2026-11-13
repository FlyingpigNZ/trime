// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.osfans.trime.core.RimeUiState
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.schema.DefaultKeyboardRegistry
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.util.DiagnosticLog

/**
 * What an observed ascii-mode change means for the keyboard the user is on:
 * [switchTo] is the layout to select (`null` = stay), [remember] whether the new
 * mode becomes this keyboard's remembered mode.
 */
internal data class AsciiModeEffect(
    val switchTo: String?,
    val remember: Boolean,
)

/**
 * Decide what an ascii-mode change does.
 *
 * Entering ascii hands the keyboard over to the layout it declares
 * (`ascii_keyboard`), and leaving it hands it back — see [asciiLayoutTarget]. A
 * keyboard whose keys encode a *scheme-specific* code (the 小鹤双拼14键 token
 * keyboard sends `A`..`N`, meaningful only to its own folded scheme) would
 * otherwise stay attached in the other mode and type those internal codes out
 * as literal English letters.
 *
 * Only a keyboard the user *stays* on remembers the new mode: one that hands
 * the other mode over to a layout of its own must not inherit it (`syncAsciiMode`
 * replays that memory on every later selection — the reason a single transient
 * English state used to stick), and a *forced* window
 * ([KeyboardSwitcher.applyStartInputPolicy]'s password/number fields) is
 * temporary by construction. Every other keyboard keeps the upstream "remember
 * what the user last had" behavior.
 */
internal fun asciiModeEffect(
    currentKeyboardId: String,
    asciiKeyboards: Map<String, String>,
    asciiMode: Boolean,
    hasAsciiKeyboard: Boolean,
    forcedAsciiWindow: Boolean,
): AsciiModeEffect {
    val switchTo = asciiLayoutTarget(currentKeyboardId, asciiKeyboards, asciiMode)
    val remember = switchTo == null && !hasAsciiKeyboard && !forcedAsciiWindow
    return AsciiModeEffect(switchTo = switchTo, remember = remember)
}

/**
 * The keyboard to select when the engine's ascii-mode becomes [asciiMode], or
 * `null` to stay on [currentKeyboardId].
 *
 * Mirror image of the `.ascii` resolution, which reads the *current* keyboard's
 * declared `ascii_keyboard`: entering ascii goes to that layout, and leaving it
 * goes back to the keyboard that declared the layout we are on. A keyboard
 * without an `ascii_keyboard` never moves (the app does not guess a layout for
 * a data-declared mode change), and the return trip only happens while a single
 * preset claims the layout we are on — with several claimants (e.g. two
 * schemata sharing one English layout) there is no honest answer, so nothing
 * moves.
 *
 * @param asciiKeyboards preset keyboard id -> its declared `ascii_keyboard`
 *   (empty for keyboards that do not declare one)
 */
internal fun asciiLayoutTarget(
    currentKeyboardId: String,
    asciiKeyboards: Map<String, String>,
    asciiMode: Boolean,
): String? {
    val isPreset: (String) -> Boolean = { it.isNotEmpty() && asciiKeyboards.containsKey(it) }
    if (asciiMode) {
        val ascii = asciiKeyboards[currentKeyboardId] ?: return null
        return ascii.takeIf { isPreset(it) && it != currentKeyboardId }
    }
    return asciiKeyboards.entries
        .filter { it.value == currentKeyboardId && isPreset(it.key) && it.key != currentKeyboardId }
        .singleOrNull()
        ?.key
}

/**
 * IME policy for keyboard selection: resolves symbolic targets (`.default`,
 * `.next`, `.ascii`, ...), applies explicit schema→layout bindings, and syncs
 * ascii-mode with the engine.
 *
 * Owns the [Keyboard] model cache and the switch state
 * (`currentKeyboardId`/`lastKeyboardId`/`lastLockKeyboardId`/`tempAsciiMode`).
 * Rendering (views, height, caps-state) stays in [KeyboardWindow].
 */
class KeyboardSwitcher(
    private val context: Context,
    private val theme: Theme,
    private val rime: RimeSession,
    private val service: TrimeInputMethodService,
    private val defaultKeyboards: DefaultKeyboardRegistry = DefaultKeyboardRegistry.Empty,
) {
    private val presetKeyboardIds = theme.presetKeyboards.keys.toList()

    /** Preset id -> the `ascii_keyboard` it declares (empty when it declares none). */
    private val presetAsciiKeyboards: Map<String, String> =
        theme.presetKeyboards.mapValues { it.value.asciiKeyboard }

    var currentKeyboardId: String = ""
        private set
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null

    private val keyboards = mutableMapOf<String, Keyboard>()

    /**
     * Width (px) the cached [Keyboard]s lay their rows out on. Supplied by
     * [KeyboardWindow] — the measured container first, the windowing-system
     * estimate only until the container has been laid out.
     */
    var allowedWidth: Int = 0
        private set

    /**
     * Set the layout width every [Keyboard] is built with. A changed width
     * invalidates the cache, because each cached layout has the previous width
     * baked into its key geometry; returns whether that happened (i.e. whether
     * the caller must re-attach the rebuilt keyboard).
     */
    fun updateAllowedWidth(width: Int): Boolean {
        if (width <= 0 || width == allowedWidth) return false
        allowedWidth = width
        invalidateKeyboardCache()
        return true
    }

    /**
     * Remembered ascii-mode per keyboard id, carried across
     * [invalidateKeyboardCache]: the cached models are dropped on an
     * orientation/layout-width change, and without this the rebuilt keyboard
     * would start from the theme default and syncAsciiMode would reset the
     * user's ascii state.
     */
    private val savedAsciiModes = mutableMapOf<String, Boolean>()

    val currentKeyboard: Keyboard? get() = keyboards[currentKeyboardId]
    val currentUiState: RimeUiState get() = rime.uiState.value

    private fun selectKeyboardConfig(name: String): TextKeyboard? = theme.presetKeyboards[name] ?: theme.presetKeyboards["default"]

    private fun getOrCreateKeyboard(id: String): Keyboard {
        val current = context.currentKeyboardMetrics()
        keyboards[id]?.let { cached ->
            if (cached.metrics == current) return cached
            // The cached model was built with other metrics (density, font scale
            // or landscape mode) than the ones in effect now: its px geometry
            // describes a different keyboard than the container we would draw it
            // into. Drop the whole cache instead of reusing it. This covers the
            // configuration changes that never arrive as a callback, which is
            // the case `onConfigurationChanged` alone cannot guarantee.
            DiagnosticLog.w("keyboard", "stale keyboard id=$id built=${cached.metrics} now=$current")
            invalidateKeyboardCache()
        }
        return Keyboard(context, theme, allowedWidth, selectKeyboardConfig(id), rime).also {
            it.lastAsciiMode = savedAsciiModes[id] ?: it.asciiMode
            keyboards[id] = it
        }
    }

    /**
     * Drop the cached [Keyboard] models. Their layout metrics (allowed width,
     * split ratio, keyboard height, paddings) are computed at construction
     * from the current orientation, so they must be rebuilt after a
     * configuration change. The remembered ascii-modes are carried over.
     */
    fun invalidateKeyboardCache() {
        savedAsciiModes.clear()
        keyboards.forEach { (id, keyboard) -> savedAsciiModes[id] = keyboard.lastAsciiMode }
        keyboards.clear()
    }

    /** Resolve a symbolic or literal keyboard id to an actual keyboard id. */
    fun resolveKeyboard(target: String): String {
        val currentIdx = presetKeyboardIds.indexOfFirst { currentKeyboardId == it }
        val dot =
            when (target) {
                ".default" -> resolveDefaultKeyboard()
                ".prior" -> presetKeyboardIds.getOrNull(currentIdx - 1) ?: currentKeyboardId
                ".next" -> presetKeyboardIds.getOrNull(currentIdx + 1) ?: currentKeyboardId
                ".last" -> lastKeyboardId
                ".last_lock" -> lastLockKeyboardId
                ".ascii" -> {
                    var ascii = currentKeyboard?.asciiKeyboard
                    if (ascii.isNullOrEmpty()) {
                        ascii = lastLockKeyboardId
                    }
                    if (presetKeyboardIds.contains(ascii)) ascii else currentKeyboardId
                }
                else -> {
                    target.ifEmpty {
                        if (currentKeyboard?.isLock == true) currentKeyboardId else lastLockKeyboardId
                    }
                }
            }
        var final = dot.ifEmpty { resolveDefaultKeyboard() }

        // 切换到横屏布局
        if (service.isLandscapeMode()) {
            val landscape = theme.presetKeyboards[final]?.landscapeKeyboard ?: ""
            if (landscape.isNotEmpty() && presetKeyboardIds.contains(landscape)) final = landscape
        } else {
            // 转回竖屏后，横屏布局变体应还原为基础布局
            final = baseOfLandscapeVariant(final)
        }
        return final
    }

    /**
     * Reverse mapping of the landscape substitution above: an id that is some
     * preset's `landscapeKeyboard` variant belongs to landscape mode only, so
     * resolving it in portrait mode returns the base preset instead.
     */
    private fun baseOfLandscapeVariant(id: String): String = presetKeyboardIds.firstOrNull { preset ->
        preset != id && theme.presetKeyboards[preset]?.landscapeKeyboard == id
    } ?: id

    private fun resolveDefaultKeyboard(): String {
        // Explicit tier-3/IME-package binding wins.
        val currentSchema = rime.uiState.value.schemaId
        defaultKeyboards.defaultKeyboardFor(currentSchema)?.let { bound ->
            if (presetKeyboardIds.contains(bound)) {
                return bound
            }
        }
        // A theme may still name a keyboard after the schema id.
        if (presetKeyboardIds.contains(currentSchema)) {
            return currentSchema
        }
        // Otherwise fall back to the theme's explicit default keyboard.
        return "default".takeIf { presetKeyboardIds.contains(it) }
            ?: presetKeyboardIds.firstOrNull()
            ?: ""
    }

    /**
     * Apply an observed engine ascii-mode change to the keyboard state: remember
     * it when the user stays on this layout, and return the layout to switch to
     * (`null` = stay). See [asciiModeEffect] for the policy.
     *
     * Callers pass every observed `ascii_mode` change — the mode keys
     * (`Eisu_toggle`), the 「万象状态面板」 switch and hotkeys all arrive as engine
     * option changes, so this is where the user's intent is visible. Recording it
     * at *detach* time instead (as upstream did) is what let one transient
     * English state become a keyboard's permanent preference.
     */
    fun onAsciiModeChanged(asciiMode: Boolean): String? {
        val effect =
            asciiModeEffect(
                currentKeyboardId = currentKeyboardId,
                asciiKeyboards = presetAsciiKeyboards,
                asciiMode = asciiMode,
                hasAsciiKeyboard = currentKeyboard?.hasAsciiKeyboard == true,
                forcedAsciiWindow = tempAsciiMode != null,
            )
        if (effect.remember) currentKeyboard?.lastAsciiMode = asciiMode
        return effect.switchTo
    }

    /**
     * Select and prepare the keyboard for [target], updating switch state and
     * syncing ascii-mode with the engine. Returns the [Keyboard] to render.
     */
    fun selectKeyboard(target: String): Keyboard {
        val resolved = resolveKeyboard(target)
        // Persist the resolved keyboard so the settings UI (which has no Rime
        // session) can preview the layout the user last typed on — a schema
        // switch that resolves to another keyboard is reflected there too.
        // Only write on an actual change (reselects are no-ops).
        if (resolved.isNotEmpty() && resolved != currentKeyboardId) {
            AppPrefs.defaultInstance().session.lastKeyboard.setValue(resolved)
        }
        // ".last" returns to the keyboard that was current before the last
        // real switch; navigation commands and no-op reselects must not
        // overwrite that memory.
        if (resolved != currentKeyboardId && !target.startsWith(".")) {
            lastKeyboardId = currentKeyboardId
        }
        currentKeyboardId = resolved

        val keyboard = getOrCreateKeyboard(resolved)
        if (keyboard.isLock) lastLockKeyboardId = resolved
        syncAsciiMode(keyboard)
        return keyboard
    }

    /** Compute the keyboard target for a new input session (pure policy). */
    fun startInputTarget(info: EditorInfo): String = when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
        EditorInfo.IME_FLAG_FORCE_ASCII -> ".ascii"
        else -> {
            when (info.inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME,
                -> "number"
                InputType.TYPE_CLASS_TEXT -> {
                    when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                        -> ".ascii"
                        else -> ""
                    }
                }
                else -> ""
            }
        }
    }

    /**
     * The ascii-mode policy for a new input session.
     * Must be called after the keyboard switch so [currentKeyboard] is the
     * newly selected one (mirrors the original KeyboardWindow ordering).
     */
    fun applyStartInputPolicy(targetKeyboard: String) {
        val isAsciiMode = rime.uiState.value.isAsciiMode
        if (targetKeyboard == ".ascii" || targetKeyboard == "number") {
            if (tempAsciiMode == null) {
                tempAsciiMode = isAsciiMode
            }
            if (!isAsciiMode) {
                service.postRimeJob { setRuntimeOption("ascii_mode", true) }
            }
        } else {
            tempAsciiMode?.let { saved ->
                if (isAsciiMode != saved) {
                    service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                }
                tempAsciiMode = null
            } ?: currentKeyboard?.let {
                if (theme.generalStyle.resetAsciiModeOnFocusChange) {
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                    if (isAsciiMode != targetMode) {
                        service.postRimeJob { setRuntimeOption("ascii_mode", targetMode) }
                    }
                }
            }
        }
    }

    private fun syncAsciiMode(keyboard: Keyboard) {
        val currentMode = rime.uiState.value.isAsciiMode
        val targetMode = if (keyboard.resetAsciiMode) keyboard.asciiMode else keyboard.lastAsciiMode
        if (currentMode != targetMode) {
            service.postRimeJob {
                commitComposition()
                setRuntimeOption("ascii_mode", targetMode)
            }
        }
    }
}
