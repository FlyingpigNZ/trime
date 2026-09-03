// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.disambiguation

import com.osfans.trime.data.theme.SchemaExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class T9PinyinDecoderTest :
    StringSpec({

        fun full(syllables: List<SchemaExtension.T9Disambiguation.Syllable>) = T9PinyinDecoder(
            SchemaExtension.T9Disambiguation(
                enabled = true,
                inputMethod = SchemaExtension.T9Disambiguation.InputMethod.FULL,
                syllables = syllables,
            ),
        )

        fun flypy(syllables: List<SchemaExtension.T9Disambiguation.Syllable>) = T9PinyinDecoder(
            SchemaExtension.T9Disambiguation(
                enabled = true,
                inputMethod = SchemaExtension.T9Disambiguation.InputMethod.FLYPY,
                syllables = syllables,
            ),
        )

        fun syl(
            pinyin: String,
            t9: String,
            flypyT9: String = "",
            flypyCode: String = "",
        ) = SchemaExtension.T9Disambiguation.Syllable(
            pinyin = pinyin,
            t9Code = t9,
            flypyCode = flypyCode,
            flypyT9Code = flypyT9,
        )

        val sample = listOf(
            // hao: h + ao(c) -> hc -> 42 ; gao: g + ao -> gc -> 42
            syl("hao", "426", "42"),
            syl("gao", "426", "42"),
            // han: h + an(j) -> hj -> 45 ; gan: g + an -> gj -> 45
            syl("han", "426", "45"),
            syl("gan", "426", "45"),
            // ni: n + i -> ni -> 64
            syl("ni", "64", "64"),
            // ga/ha: 1-char syllables with t9 code 42 (for decodeLeading)
            syl("ga", "42", "44"),
            syl("ha", "42", "44"),
            syl("hao", "426", "42"), // dup on purpose: decoder must dedupe
        )

        "full-pinyin: 426 lists every single-syllable parse" {
            full(sample).decode("426") shouldContainExactlyInAnyOrder listOf(
                listOf("hao"),
                listOf("gao"),
                listOf("han"),
                listOf("gan"),
            )
        }

        "full-pinyin: multi-syllable input enumerates complete splits" {
            // 4266: 426 + 6, and 6 alone is not a syllable; only full-length
            // parses are returned.
            full(sample).decode("4266") shouldBe emptyList()
            // 64426: ni(64) + 426 -> "ni hao/gao/han/gan"
            full(sample).decodeToDisplay("64426") shouldContainExactlyInAnyOrder listOf(
                "ni hao",
                "ni gao",
                "ni han",
                "ni gan",
            )
        }

        "full-pinyin: empty input yields empty" {
            full(sample).decode("") shouldBe emptyList()
        }

        "full-pinyin: unknown digits yield empty" {
            full(sample).decode("999999") shouldBe emptyList()
        }

        "flypy: 42 lists the full pinyin parses (not the key codes)" {
            flypy(sample).decodeToDisplay("42") shouldContainExactlyInAnyOrder listOf(
                "hao",
                "gao",
            )
        }

        "flypy: odd-length input yields empty" {
            flypy(sample).decode("421") shouldBe emptyList()
        }

        "flypy: multi-syllable two-digit grouping" {
            // 4264 -> 42 + 64 -> (hao|gao) + ni
            flypy(sample).decodeToDisplay("4264") shouldContainExactlyInAnyOrder listOf(
                "hao ni",
                "gao ni",
            )
        }

        "decodeLeading: 426 shows every leading pinyin longest-first" {
            full(sample).decodeLeading("426").map { it.pinyin[0] to it.consumed } shouldContainExactly
                listOf(
                    "gan" to 3,
                    "gao" to 3,
                    "han" to 3,
                    "hao" to 3,
                    "ga" to 2,
                    "ha" to 2,
                    "g" to 1,
                    "h" to 1,
                )
        }

        "decodeLeading: empty input yields empty" {
            full(sample).decodeLeading("") shouldBe emptyList()
        }

        "decodeLeading: leading pinyin shortest consumed wins" {
            // `hao` via `426`; `ha` via `42` — both distinct pinyin, distinct cost.
            full(sample).decodeLeading("426").firstOrNull { it.pinyin[0] == "hao" }?.consumed shouldBe 3
            full(sample).decodeLeading("426").firstOrNull { it.pinyin[0] == "ha" }?.consumed shouldBe 2
        }

        "decodeLeading flypy: code is the 小鹤双拼 code, not the full pinyin" {
            // Input `42` should offer the complete syllable `cha` (flypy code
            // `ia`: i=ch, a=a). The panel shows `cha` but feeding Rime uses the
            // 双拼 code `ia` — this is the fix that stops Rime splitting `cha`
            // into the stray `ch`+`a`.
            val flypySample = listOf(syl("cha", "242", "42", "ia"))
            flypy(flypySample).decodeLeading("42").map { it.pinyin[0] to it.code } shouldContainExactly
                listOf("cha" to "ia")
        }

        "decodeLeading flypy: never offers a bare initial (its code would be invalid 双拼)" {
            // `gao` starts with digit `4`; a bare initial `g` would be a valid
            // FULL-pinyin surrogate but is NOT a valid 小鹤双拼 code (each 双拼
            // syllable is two digits). FLYPY must not offer it. Input `4266` is
            // not even a complete 双拼 grouping, but the single-initial branch
            // must not fire and produce a bogus `g`.
            val flypySample = listOf(syl("gao", "426", "62", "gc"))
            flypy(flypySample).decodeLeading("4266").map { it.pinyin[0] } shouldBe emptyList()
        }

        "decodeLeading flypy: orders by full-pinyin length, not by code/digits" {
            // All these 双拼 codes fold to the same two digits `84`, so every
            // candidate consumes the same digits; the list must still be
            // longest-full-pinyin first (shang > zheng > tang > shi > ti), with
            // equal lengths in pinyin order.
            val flypySample = listOf(
                syl("shi", "744", "84", "ui"),
                syl("shang", "74264", "84", "uh"),
                syl("tang", "8264", "84", "th"),
                syl("zheng", "94364", "84", "vg"),
                syl("ti", "84", "84", "ti"),
            )
            flypy(flypySample).decodeLeading("84").map { it.pinyin[0] } shouldContainExactly
                listOf("shang", "zheng", "tang", "shi", "ti")
        }
    })
