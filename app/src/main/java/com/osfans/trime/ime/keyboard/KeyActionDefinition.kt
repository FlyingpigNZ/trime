// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.PresetKey

/**
 * Immutable result of parsing a [KeyActionToken] against the theme's
 * `preset_keys`. Pure definition: no hidden globals, no engine access.
 *
 * Interpretation (labels, shift-lock semantics) lives in [KeyAction], which
 * takes a `RimeUiState` snapshot as a parameter. [command] is parsed into a
 * typed [KeyActionCommand] so dispatch is compile-time checked.
 */
data class KeyActionDefinition(
    val code: Int,
    val modifier: Int,
    val command: KeyActionCommand,
    val option: String,
    val select: String,
    val toggle: String,
    val commit: String,
    val shiftLock: String,
    val isFunctional: Boolean,
    val isRepeatable: Boolean,
    val isSticky: Boolean,
    val isSlideCursor: Boolean,
    val isSlideDelete: Boolean,
    val text: String,
    val label: String,
    val shiftLabel: String,
    val preview: String?,
    val states: List<String>,
) {
    val isModifierKey: Boolean
        // Trime把function键消费掉了，因此键盘只处理function键以外的修饰键
        get() = KeyEvent.isModifierKey(code) && code != KeyEvent.KEYCODE_FUNCTION

    val modifierKeyOnMask: Int
        get() = getModifierKeyOnMask(code)

    companion object {
        private val BRACED_PATTERN = Regex("""\{[^{}]+\}""")

        /**
         * Parse a [KeyActionToken] (plain string or inline `{commit,text,
         * label}` map) against the theme's [presetKeys].
         */
        fun parse(
            token: KeyActionToken,
            presetKeys: Map<String, PresetKey>,
            labelProvider: KeyLabelProvider = AndroidKeyLabelProvider,
        ): KeyActionDefinition {
            var code = 0
            var modifier = 0
            var commandName = ""
            var option = ""
            var select = ""
            var toggle = ""
            var commit = ""
            var shiftLock = ""
            var isFunctional = false
            var isRepeatable = false
            var isSticky = false
            var isSlideCursor = false
            var isSlideDelete = false
            var text = ""
            var preview: String? = null
            var states: List<String> = emptyList()
            var label: String

            when (token) {
                is KeyActionToken.Plain -> {
                    // match like: { x: BackSpace } -> preset_keys/BackSpace: {..., send: BackSpace }
                    val preset = presetKeys[token.token]
                    if (preset != null) {
                        commandName = preset.command
                        option = preset.option
                        select = preset.select
                        toggle = preset.toggle
                        preview = preset.preview
                        shiftLock = preset.shiftLock
                        commit = preset.commit
                        text = preset.text
                        isSticky = preset.sticky
                        isRepeatable = preset.repeatable
                        isFunctional = preset.functional
                        isSlideCursor = preset.slideCursor
                        isSlideDelete = preset.slideDelete
                        states = preset.states

                        label = preset.label

                        val (keycode, modifiers) = KeyCode.parse(preset.send)
                        if (keycode != 0 || modifiers != 0) {
                            code = keycode
                            modifier = modifiers
                        } else if (commandName.isNotEmpty()) {
                            code = KeyEvent.KEYCODE_FUNCTION
                        }
                    } else {
                        // match like: { x: "{Control+a}" }
                        val (keycode, modifiers) = KeyCode.parse(token.token)
                        if (keycode != 0 || modifiers != 0) {
                            code = keycode
                            modifier = modifiers
                            label = ""
                        } else {
                            // match like: { x: 1 } or { x: q } ...
                            code = KeyCode.nameToKeyCode(token.token)
                            // match like: { x: "(){Left}" } (key sequence to simulate)
                            if (token.token.isNotEmpty() && !KeyCode.isStandardKey(code)) {
                                text = token.token
                                label = token.token.replace(BRACED_PATTERN, "")
                            } else {
                                label = ""
                            }
                        }
                    }
                }
                // match: { x: { commit: a, text: b, label: c } }
                is KeyActionToken.Inline -> {
                    commit = token.token.commit ?: ""
                    text = token.token.text ?: ""
                    label = token.token.label ?: ""
                }
            }

            label = label.ifEmpty {
                when (code) {
                    KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_SPACE -> ""
                    else -> labelProvider.getDisplayLabel(code, modifier)
                }
            }
            var shiftLabel = label
            if (KeyCode.isStandardKey(code) && labelProvider.isPrintingKey(code)) {
                val charCode = labelProvider.get(code, modifier or KeyEvent.META_SHIFT_ON)
                if (charCode != 0) {
                    shiftLabel = charCode.toChar().toString()
                }
            }

            return KeyActionDefinition(
                code = code,
                modifier = modifier,
                command = KeyActionCommand.fromName(commandName),
                option = option,
                select = select,
                toggle = toggle,
                commit = commit,
                shiftLock = shiftLock,
                isFunctional = isFunctional,
                isRepeatable = isRepeatable,
                isSticky = isSticky,
                isSlideCursor = isSlideCursor,
                isSlideDelete = isSlideDelete,
                text = text,
                label = label,
                shiftLabel = shiftLabel,
                preview = preview,
                states = states,
            )
        }

        fun getModifierKeyOnMask(keycode: Int): Int = when (keycode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
            KeyEvent.KEYCODE_SYM -> KeyEvent.META_SYM_ON
            else -> 0
        }
    }
}

/**
 * Typed function commands parsed from the theme's `preset_keys` `command`
 * field, so dispatch is exhaustive and compile-time checked instead of a
 * stringly-typed `when`.
 */
sealed interface KeyActionCommand {
    data object LiquidKeyboard : KeyActionCommand
    data object MenuKeyboard : KeyActionCommand
    data object ClipboardWindow : KeyActionCommand
    data object SetColorScheme : KeyActionCommand
    data object Broadcast : KeyActionCommand
    data object Clipboard : KeyActionCommand
    data object Commit : KeyActionCommand
    data object Date : KeyActionCommand
    data object Run : KeyActionCommand
    data object Apply : KeyActionCommand
    data object ShareText : KeyActionCommand
    data object SelectCandidate : KeyActionCommand

    /** Unknown command: falls back to intent-based dispatch. */
    data class Intent(val command: String) : KeyActionCommand

    companion object {
        fun fromName(name: String): KeyActionCommand = when (name) {
            "liquid_keyboard" -> LiquidKeyboard
            "menu_keyboard" -> MenuKeyboard
            "clipboard_window" -> ClipboardWindow
            "set_color_scheme" -> SetColorScheme
            "broadcast" -> Broadcast
            "clipboard" -> Clipboard
            "commit" -> Commit
            "date" -> Date
            "run" -> Run
            "apply" -> Apply
            "share_text" -> ShareText
            "select_candidate" -> SelectCandidate
            else -> Intent(name)
        }
    }
}
