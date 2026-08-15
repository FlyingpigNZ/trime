// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KeyActionCommandTest :
    StringSpec({
        "known commands map to sealed command objects" {
            KeyActionCommand.fromName("liquid_keyboard") shouldBe KeyActionCommand.LiquidKeyboard
            KeyActionCommand.fromName("menu_keyboard") shouldBe KeyActionCommand.MenuKeyboard
            KeyActionCommand.fromName("clipboard_window") shouldBe KeyActionCommand.ClipboardWindow
            KeyActionCommand.fromName("set_color_scheme") shouldBe KeyActionCommand.SetColorScheme
            KeyActionCommand.fromName("set_theme") shouldBe KeyActionCommand.SetTheme
            KeyActionCommand.fromName("broadcast") shouldBe KeyActionCommand.Broadcast
            KeyActionCommand.fromName("clipboard") shouldBe KeyActionCommand.Clipboard
            KeyActionCommand.fromName("commit") shouldBe KeyActionCommand.Commit
            KeyActionCommand.fromName("date") shouldBe KeyActionCommand.Date
            KeyActionCommand.fromName("run") shouldBe KeyActionCommand.Run
            KeyActionCommand.fromName("apply") shouldBe KeyActionCommand.Apply
            KeyActionCommand.fromName("share_text") shouldBe KeyActionCommand.ShareText
            KeyActionCommand.fromName("select_candidate") shouldBe KeyActionCommand.SelectCandidate
        }

        "unknown commands fall back to Intent" {
            KeyActionCommand.fromName("my_custom_action") shouldBe
                KeyActionCommand.Intent("my_custom_action")
            KeyActionCommand.fromName("") shouldBe KeyActionCommand.Intent("")
        }
    })
