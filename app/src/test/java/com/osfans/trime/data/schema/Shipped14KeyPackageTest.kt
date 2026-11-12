// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.get
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.zip.ZipFile

/**
 * Cross-file pins on the shipped 万象14键 package.
 *
 * The 小鹤双拼14键 keyboard does not send letters: each of its 14 keys sends one
 * internal token (`QW`→`A` … `M`→`N`) that only means something to its own
 * folded Rime scheme, while the *displayed* letter (and the preedit echo in
 * `super_comment_preedit.lua`) comes from a separate mapping. Every piece of
 * that chain lives in a different file, so an inconsistent rebuild of the
 * package fails silently at runtime — the keyboard types its internal codes out
 * as literal English letters and Rime converts nothing, which is exactly the
 * bug this file guards:
 *
 * - keyboard sends ↔ the schema's `/14jian-token` fold (same 14-token space),
 * - `ascii_keyboard` declared ↔ that preset existing (the English layout the
 *   14键 keyboard hands over to; see `asciiLayoutTarget`),
 * - the schema's switches ↔ `switcher/save_options` (a switch that is not
 *   listed silently reverts to its default on every deploy/restart).
 */
class Shipped14KeyPackageTest :
    StringSpec({
        val packageZip = File("../sample_theme_schemas/万象14键-nogram.zip")
        val schemaId = "wanxiang_14jian"
        val tokenKeyboard = "14jian"

        /** The 14 keys in keyboard order, with the token each one sends. */
        val expectedTokens = "ABCDEFGHIJKLMN"

        fun ZipFile.text(name: String): String = getInputStream(getEntry(name) ?: error("$name missing from ${this@text}")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

        fun ZipFile.yaml(name: String): Node.Mapping = Yaml.Default.parseToYamlNode(text(name)) as? Node.Mapping
            ?: error("$name is not a mapping")

        fun Node.Mapping.presetKeys(): Node.Mapping = this["preset_keyboards"]?.mapping ?: error("preset_keyboards missing")

        "the 14键 keys send exactly the tokens its schema folds into" {
            ZipFile(packageZip).use { zip ->
                val keys =
                    zip.yaml("keyboard.yaml")
                        .presetKeys()[tokenKeyboard]?.mapping
                        ?.get("keys")?.sequence
                        ?: error("preset_keyboards.$tokenKeyboard.keys missing")
                val sends =
                    keys.mapNotNull { it.mapping?.get("click")?.string }
                        .filter { it.startsWith("14key") }
                sends.size shouldBe expectedTokens.length
                val behavior =
                    zip.yaml("behavior.yaml")["preset_keys"]?.mapping
                        ?: error("preset_keys missing")
                val tokens =
                    sends.map { click ->
                        behavior[click]?.mapping?.get("send")?.string ?: error("preset $click missing")
                    }
                tokens.joinToString("") shouldBe expectedTokens

                // The fold that makes those tokens findable must be the one the
                // schema references — a representative-letter fold would leave
                // every typed token unmatched.
                val schema = zip.yaml("$schemaId.schema.yaml")
                val algebra =
                    schema["speller"]?.mapping?.get("algebra")?.mapping
                        ?.get("__patch")?.sequence
                        ?.mapNotNull { it.string }
                        ?: error("speller/algebra/__patch missing")
                algebra shouldContain "wanxiang_algebra:/14jian-token"
            }
        }

        "the token keyboard hands ascii mode to a preset it declares" {
            ZipFile(packageZip).use { zip ->
                val presets = zip.yaml("keyboard.yaml").presetKeys()
                val asciiKeyboard =
                    presets[tokenKeyboard]?.mapping?.get("ascii_keyboard")?.string ?: ""
                asciiKeyboard shouldBe "letter_14jian"
                // The hand-over target must be a real preset, otherwise
                // asciiLayoutTarget cannot select it and the 14键 layout stays
                // attached in English mode (typing its tokens out as a, b, c …).
                (presets[asciiKeyboard]?.mapping != null) shouldBe true
            }
        }

        "every switch the schema declares is remembered or explicitly not" {
            ZipFile(packageZip).use { zip ->
                val switches =
                    zip.yaml("$schemaId.schema.yaml")["switches"]?.sequence
                        ?: error("switches missing")
                val declared =
                    switches
                        .flatMap { switch ->
                            val map = switch.mapping ?: return@flatMap emptyList()
                            listOfNotNull(map["name"]?.string) +
                                (map["options"]?.sequence?.mapNotNull { it.string } ?: emptyList())
                        }.distinct()
                val saved =
                    zip.yaml("rime/default.yaml")["switcher"]?.mapping
                        ?.get("save_options")?.sequence
                        ?.mapNotNull { it.string }
                        ?: error("switcher/save_options missing")

                // ascii_mode is deliberately not remembered: the keyboard data
                // (and the app's ascii policy) owns the mode, and restoring a
                // stale English on startup is the failure this pin exists for.
                declared.filterNot { it in saved } shouldBe listOf("ascii_mode")
            }
        }
    })
