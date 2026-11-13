// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.disambiguation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PinyinDisambiguationStateTest :
    StringSpec({
        // 小鹤双拼14键: the keyboard sends the 14 representative letters, so the
        // typed code is lowercase letters (T9 modes use digits, same model).
        fun letterState() = PinyinDisambiguationState { it in 'a'..'z' }

        fun PinyinDisambiguationState.typeAll(code: String) = code.forEach { appendTyped(it) }

        "a panel pick narrows the feed and leaves the untouched code as the tail" {
            val state = letterState()
            state.typeAll("vzmo")
            state.pendingCode shouldBe "vzmo"
            state.feed shouldBe "vzmo"

            state.confirm("zhou", "vz", 2)
            state.pendingCode shouldBe "mo"
            state.feed shouldBe "vz'mo"

            state.confirm("mo", "mo", 2)
            state.pendingCode shouldBe ""
            // Nothing left un-confirmed: no dangling separator.
            state.feed shouldBe "vz'mo"
        }

        "a 词组 pick is recorded as the typed run it consumed" {
            val state = letterState()
            state.typeAll("llgevzmo")
            state.absorbConsumedPrefix(tailLength = 4) shouldBe true
            state.confirmed.single().pinyin shouldBe ""
            state.feed shouldBe "llge'vzmo"
            // The same tail never advances twice.
            state.absorbConsumedPrefix(tailLength = 4) shouldBe false
        }

        "Escape-truncated input drops the syllables the engine no longer holds" {
            // Reported 2026-11-13: type llgevzmo, pick 两个 (a 词组), panel-pick
            // zhou + mo, then Escape. librime truncates the raw input at the
            // last segment ("llge'vz") and keeps the composition, so no
            // empty-composition reset happens.
            val state = letterState()
            state.typeAll("llgevzmo")
            state.absorbConsumedPrefix(tailLength = 4)
            state.confirm("zhou", "vz", 2)
            state.confirm("mo", "mo", 2)
            state.feed shouldBe "llge'vz'mo"

            state.reconcile("llge'vz")

            state.confirmed.map { it.pinyin } shouldBe listOf("", "zhou")
            state.typedCode shouldBe "llgevz"
            state.pendingCode shouldBe ""

            // Retyping vz must feed the engine's actual state, not the stale
            // zhou/mo confirmations (`zhou'mo'zhou` before the fix).
            state.appendTyped('v')
            state.appendTyped('z')
            state.pendingCode shouldBe "vz"
            state.confirm("zhou", "vz", 2)
            state.feed shouldBe "llge'vz'vz"
        }

        "Escape with nothing confirmed truncates the typed code" {
            val state = letterState()
            state.typeAll("llgevzmo")
            state.reconcile("llge")
            state.typedCode shouldBe "llge"
            state.feed shouldBe "llge"
        }

        "a cut inside a confirmed code spelling keeps the exposed prefix typed" {
            val state = letterState()
            state.typeAll("llgevzmo")
            state.absorbConsumedPrefix(tailLength = 4)
            state.confirm("zhou", "vz", 2)
            state.confirm("mo", "mo", 2)

            // "vz" is cut in half: the syllable it stood for is gone, the
            // exposed "v" is what the engine still holds.
            state.reconcile("llge'v")

            state.confirmed.map { it.code } shouldBe listOf("llge")
            state.typedCode shouldBe "llgev"
            state.pendingCode shouldBe "v"
        }

        "characters typed after a pick are not mistaken for a shrink" {
            // Rime appends typed keys to the raw input; only a pick re-feeds
            // it. The engine's raw is therefore code + quotes + a longer tail.
            val state = letterState()
            state.typeAll("llgevzmo")
            state.absorbConsumedPrefix(tailLength = 4)
            state.confirm("zhou", "vz", 2)
            state.typeAll("vz")

            state.reconcile("llge'vz'movz")

            state.typedCode shouldBe "llgevzmovz"
            state.pendingCode shouldBe "movz"
            state.feed shouldBe "llge'vz'movz"
        }

        "engine characters we never recorded are adopted when typed-code shaped" {
            val state = letterState()
            state.typeAll("vz")
            state.reconcile("vzmo")
            state.typedCode shouldBe "vzmo"
            state.pendingCode shouldBe "vzmo"
        }

        "input that is no longer our model of it drops the owned state" {
            val state = letterState()
            state.typeAll("vzmo")
            state.confirm("zhou", "vz", 2)
            // e.g. a caret edit moved the input somewhere we cannot map.
            state.reconcile("something-else")
            state.isEmpty shouldBe true
            state.feed shouldBe ""
        }

        "undo pops the last confirmation and rebuilds the feed" {
            val state = letterState()
            state.typeAll("llgevzmo")
            state.absorbConsumedPrefix(tailLength = 4)
            state.confirm("zhou", "vz", 2)

            val popped = state.undo()
            popped?.pinyin shouldBe "zhou"
            popped?.code shouldBe "vz"
            state.feed shouldBe "llge'vzmo"
            state.undo()?.pinyin shouldBe ""
            state.undo() shouldBe null
        }

        "T9 full-pinyin digits feed the chosen pinyin before the remaining digits" {
            val state = PinyinDisambiguationState { it.isDigit() }
            state.typeAll("42633")
            state.confirm("hao", "hao", 3)
            state.pendingCode shouldBe "33"
            state.feed shouldBe "hao'33"
            state.reconcile("hao'33")
            state.feed shouldBe "hao'33"
        }
    })
