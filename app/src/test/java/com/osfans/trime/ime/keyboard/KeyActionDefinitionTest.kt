// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.PresetKey
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KeyActionDefinitionTest :
    StringSpec({
        "plain key name resolves to keycode and ASCII labels" {
            val def = KeyActionDefinition.parse(
                KeyActionToken.Plain("a"),
                emptyMap(),
                AsciiKeyLabelProvider,
            )

            def.code shouldBe KeyEvent.KEYCODE_A
            def.modifier shouldBe 0
            def.label shouldBe "a"
            def.shiftLabel shouldBe "A"
            def.command shouldBe KeyActionCommand.Intent("")
        }

        "modifier syntax parses keycode and modifiers" {
            val def = KeyActionDefinition.parse(
                KeyActionToken.Plain("Control+a"),
                emptyMap(),
                AsciiKeyLabelProvider,
            )

            def.code shouldBe KeyEvent.KEYCODE_A
            def.modifier shouldBe KeyEvent.META_CTRL_ON
            def.label shouldBe "a"
            def.shiftLabel shouldBe "A"
        }

        "preset key lookup fills command, send, and label" {
            val preset =
                PresetKey(
                    command = "commit",
                    label = "go",
                    send = "Return",
                )
            val def = KeyActionDefinition.parse(
                KeyActionToken.Plain("enter"),
                mapOf("enter" to preset),
                AsciiKeyLabelProvider,
            )

            def.command shouldBe KeyActionCommand.Commit
            def.code shouldBe KeyEvent.KEYCODE_ENTER
            def.modifier shouldBe 0
            def.label shouldBe "go"
            def.shiftLabel shouldBe "go"
        }

        "preset key with unknown command keeps intent fallback" {
            val preset =
                PresetKey(
                    command = "no_such_command",
                    label = "x",
                    send = "Return",
                )
            val def = KeyActionDefinition.parse(
                KeyActionToken.Plain("custom"),
                mapOf("custom" to preset),
                AsciiKeyLabelProvider,
            )

            def.command shouldBe KeyActionCommand.Intent("no_such_command")
        }

        "inline token maps commit/text/label directly" {
            val token =
                KeyActionToken.Inline(
                    KeyActionToken.Inline.Token(
                        commit = "你好",
                        text = "hello",
                        label = "hi",
                    ),
                )
            val def = KeyActionDefinition.parse(token, emptyMap(), AsciiKeyLabelProvider)

            def.commit shouldBe "你好"
            def.text shouldBe "hello"
            def.label shouldBe "hi"
            def.shiftLabel shouldBe "hi"
            def.code shouldBe 0
        }

        "key sequence braces are kept as text and stripped from label" {
            val def = KeyActionDefinition.parse(
                KeyActionToken.Plain("(){Left}"),
                emptyMap(),
                AsciiKeyLabelProvider,
            )

            def.code shouldBe KeyEvent.KEYCODE_UNKNOWN
            def.text shouldBe "(){Left}"
            def.label shouldBe "()"
        }
    })
