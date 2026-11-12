// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the ascii-mode/layout policy behind the 14键 token keyboard.
 *
 * The bug these guard: the 小鹤双拼14键 keyboard sends one internal token per key
 * (`A`..`N`), meaningful only to its own folded Rime scheme. When the engine was
 * switched to ascii mode from outside the keyboard (the 「万象状态面板」 中文/英文
 * switch, a hotkey, `Eisu_toggle`), the Chinese layout stayed attached and typed
 * those tokens out as literal English letters — the keys "became" a, b, c …
 * while Rime converted nothing. A second defect made it stick: the keyboard
 * remembered the engine's mode *at detach time*, so one transient English state
 * was replayed on every later selection.
 */
class AsciiModePolicyTest :
    StringSpec({
        // The 万象14键 package: 14jian (中文, ascii_keyboard: letter_14jian),
        // letter_14jian (英文, declares none), number/letter/symbols.
        val packageKeyboards =
            mapOf(
                "14jian" to "letter_14jian",
                "14symbolsen" to "letter_t9",
                "letter_14jian" to "",
                "letter_t9" to "",
                "letter" to "",
                "rime_ice" to "letter",
                "double_pinyin_flypy" to "letter",
                "symbols" to "",
                "number" to "",
            )

        fun effect(
            current: String,
            asciiMode: Boolean,
            hasAsciiKeyboard: Boolean = packageKeyboards[current].orEmpty().isNotEmpty(),
            forcedAsciiWindow: Boolean = false,
        ) = asciiModeEffect(
            currentKeyboardId = current,
            asciiKeyboards = packageKeyboards,
            asciiMode = asciiMode,
            hasAsciiKeyboard = hasAsciiKeyboard,
            forcedAsciiWindow = forcedAsciiWindow,
        )

        "entering ascii mode switches to the layout the keyboard declares" {
            effect("14jian", asciiMode = true) shouldBe AsciiModeEffect(switchTo = "letter_14jian", remember = false)
            effect("14symbolsen", asciiMode = true) shouldBe AsciiModeEffect(switchTo = "letter_t9", remember = false)
        }

        "a keyboard that declares no ascii layout never moves, and remembers the mode" {
            // The app does not guess a layout for a data-declared mode change: a
            // 26键 Chinese keyboard in English mode keeps typing English with its
            // own (real) letters, which is the stock behaviour.
            effect("symbols", asciiMode = true) shouldBe AsciiModeEffect(switchTo = null, remember = true)
            effect("number", asciiMode = true) shouldBe AsciiModeEffect(switchTo = null, remember = true)
        }

        "an ascii layout already in place does not re-select itself" {
            effect("letter_14jian", asciiMode = true) shouldBe AsciiModeEffect(switchTo = null, remember = true)
        }

        "leaving ascii mode returns to the keyboard that declared this layout" {
            effect("letter_14jian", asciiMode = false) shouldBe AsciiModeEffect(switchTo = "14jian", remember = false)
            effect("letter_t9", asciiMode = false) shouldBe AsciiModeEffect(switchTo = "14symbolsen", remember = false)
        }

        "a layout claimed by several presets is left alone" {
            // `letter` is the ascii layout of two schemata (rime_ice and
            // double_pinyin_flypy); there is no honest single answer, so the
            // mode change must not move the user to an arbitrary one of them.
            effect("letter", asciiMode = false) shouldBe AsciiModeEffect(switchTo = null, remember = true)
        }

        "a Chinese layout stays put when ascii mode ends" {
            effect("14jian", asciiMode = false) shouldBe AsciiModeEffect(switchTo = null, remember = false)
        }

        "an unknown or empty declared layout is ignored" {
            val dangling = mapOf("14jian" to "not_a_preset", "x" to "")
            val result =
                asciiModeEffect(
                    currentKeyboardId = "14jian",
                    asciiKeyboards = dangling,
                    asciiMode = true,
                    hasAsciiKeyboard = true,
                    forcedAsciiWindow = false,
                )
            result shouldBe AsciiModeEffect(switchTo = null, remember = false)
        }

        "a keyboard that hands ascii over never remembers a mode of its own" {
            // 14jian hands English to letter_14jian: remembering English here
            // would be replayed by syncAsciiMode on the next selection and fight
            // the hand-over — the state that typed the tokens out as a, b, c …
            effect("14jian", asciiMode = false) shouldBe AsciiModeEffect(switchTo = null, remember = false)
        }

        "a forced ascii window is never remembered" {
            // A password field (or a number field) forces English for as long as
            // the field is focused; recording that leaves the keyboard stuck in
            // English after the field is gone.
            effect("symbols", asciiMode = true, forcedAsciiWindow = true)
                .shouldBe(AsciiModeEffect(switchTo = null, remember = false))
        }
    })
