// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.schema.DefaultKeyboardRegistry
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode

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

    var currentKeyboardId: String = ""
        private set
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null

    private val keyboards = mutableMapOf<String, Keyboard>()

    val currentKeyboard: Keyboard? get() = keyboards[currentKeyboardId]

    private fun selectKeyboardConfig(name: String): TextKeyboard? =
        theme.presetKeyboards[name] ?: theme.presetKeyboards["default"]

    private fun getOrCreateKeyboard(id: String): Keyboard =
        keyboards.getOrPut(id) {
            Keyboard(context, theme, selectKeyboardConfig(id), rime).also { it.lastAsciiMode = it.asciiMode }
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
        }
        return final
    }

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
     * Select and prepare the keyboard for [target], updating switch state and
     * syncing ascii-mode with the engine. Returns the [Keyboard] to render.
     */
    fun selectKeyboard(target: String): Keyboard {
        val resolved = resolveKeyboard(target)
        currentKeyboardId = resolved
        lastKeyboardId = resolved

        val keyboard = getOrCreateKeyboard(resolved)
        if (keyboard.isLock) lastLockKeyboardId = resolved
        syncAsciiMode(keyboard)
        KeyboardSwitcherLegacy.currentKeyboard = keyboard
        KeyboardSwitcherLegacy.currentUiState = rime.uiState.value
        return keyboard
    }

    /** Record the current keyboard's ascii-mode before it is detached. */
    fun detachCurrentKeyboard() {
        currentKeyboard?.lastAsciiMode = rime.uiState.value.isAsciiMode
    }

    /** Compute the keyboard target for a new input session (pure policy). */
    fun startInputTarget(info: EditorInfo): String =
        when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
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
