// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import com.osfans.trime.core.RimeUiState
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.PresetKey

/** [按鍵][Key]的各種事件（單擊、長按、滑動等）  */
class KeyAction(
    val definition: KeyActionDefinition,
) {
    constructor(token: KeyActionToken, presetKeys: Map<String, PresetKey>) :
        this(KeyActionDefinition.parse(token, presetKeys))

    constructor(token: String, presetKeys: Map<String, PresetKey>) :
        this(KeyActionToken.Plain(token), presetKeys)

    val code get() = definition.code
    val modifier get() = definition.modifier
    val command get() = definition.command
    val option get() = definition.option
    val select get() = definition.select
    val toggle get() = definition.toggle
    val commit get() = definition.commit
    val shiftLock get() = definition.shiftLock
    val isFunctional get() = definition.isFunctional
    val isRepeatable get() = definition.isRepeatable
    val isSticky get() = definition.isSticky
    val isSlideCursor get() = definition.isSlideCursor
    val isSlideDelete get() = definition.isSlideDelete

    val isModifierKey: Boolean
        get() = definition.isModifierKey

    val modifierKeyOnMask: Int
        get() = definition.modifierKeyOnMask

    fun isShiftLock(ui: RimeUiState): Boolean =
        when (shiftLock) {
            "long" -> false // 长按锁定
            "click" -> true // 点击锁定
            "ascii_long" -> !ui.isAsciiMode // 英文长按锁定，中文点击锁定
            else -> false
        }

    private val text get() = definition.text
    private val label get() = definition.label
    private val shiftLabel get() = definition.shiftLabel
    private val preview get() = definition.preview
    private val states get() = definition.states

    // 获取空格键的schemaName，处理初始化时可能为空的情况
    private fun getSpaceKeySchemaName(ui: RimeUiState): String = ui.schemaName.ifEmpty {
        // 如果schemaName为空，尝试使用schemaId作为显示名称
        ui.schemaId.takeIf { it.isNotEmpty() && it != ".default" } ?: ""
    }

    private fun adjustCase(
        str: String,
        keyboard: Keyboard,
        ui: RimeUiState,
    ): String =
        if (str.length == 1 && (keyboard.isShifted || (!ui.isAsciiMode && keyboard.isLabelUppercase))) {
            str.uppercase()
        } else {
            str
        }

    fun getLabel(
        keyboard: Keyboard,
        ui: RimeUiState,
    ): String {
        if (states.isNotEmpty() && toggle.isNotEmpty()) {
            return states[if (ui.options[toggle] == true) 1 else 0]
        }
        if (keyboard.isOnlyShiftOn) {
            val asciiMode = ui.isAsciiMode
            val composing = ui.isComposing
            val hookShiftNum = AppPrefs.defaultInstance().keyboard.hookShiftNum.getValue()
            val hookShiftSymbol = AppPrefs.defaultInstance().keyboard.hookShiftSymbol.getValue()
            if (!hookShiftNum && !composing && code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                return adjustCase(shiftLabel, keyboard, ui)
            }
            if (!hookShiftSymbol &&
                // TODO: 判断中英模式仅能正确处理已配置映射的符号，对于未配置映射的符号，即使在中文模式下也能上屏 Shift 切换的符号。
                asciiMode &&
                (
                    code in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_SLASH ||
                        code == KeyEvent.KEYCODE_COMMA ||
                        code == KeyEvent.KEYCODE_PERIOD
                    )
            ) {
                return adjustCase(shiftLabel, keyboard, ui)
            }
        }
        // 仅在为空格键且 label 为空时才去查询 schema 名称，先检查键码以减少无谓计算
        val displayLabel = takeIf { code == KeyEvent.KEYCODE_SPACE && label.isEmpty() }?.let { getSpaceKeySchemaName(ui) } ?: label
        return adjustCase(displayLabel, keyboard, ui)
    }

    fun getText(
        keyboard: Keyboard,
        ui: RimeUiState,
    ): String = if (text.isNotEmpty()) {
        adjustCase(text, keyboard, ui)
    } else if (keyboard.isShifted && code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z && modifier == 0) {
        if (ui.isAsciiMode) "" else adjustCase(label, keyboard, ui)
    } else {
        text
    }

    fun getPreview(
        keyboard: Keyboard,
        ui: RimeUiState,
    ): String = preview ?: getLabel(keyboard, ui)

    companion object {
        fun getModifierKeyOnMask(keycode: Int): Int = KeyActionDefinition.getModifierKeyOnMask(keycode)
    }
}
