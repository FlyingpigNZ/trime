// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SchemaLayoutManifestTest :
    StringSpec({
        "parse a minimal manifest with resources" {
            val manifest =
                SchemaLayoutManifest.parse(
                    """
                    schema_id: 14jian
                    name: 小鹤双拼14键
                    version: "0.1"
                    schema_file: 14jian.schema.yaml
                    theme_file: theme.yaml
                    layout_files:
                      - 14jian.layout.yaml
                    default_keyboard: 14jian
                    resources:
                      - backgrounds/14jian.png
                      - backgrounds/14jian.night.png
                    rime_files:
                      - rime/default.yaml
                      - rime/cn_dicts/base.dict.yaml
                    """.trimIndent(),
                )

            manifest.schemaId shouldBe "14jian"
            manifest.name shouldBe "小鹤双拼14键"
            manifest.version shouldBe "0.1"
            manifest.schemaFile shouldBe "14jian.schema.yaml"
            manifest.themeFile shouldBe "theme.yaml"
            manifest.layoutFiles shouldBe listOf("14jian.layout.yaml")
            manifest.defaultKeyboard shouldBe "14jian"
            manifest.resources shouldBe listOf(
                "backgrounds/14jian.png",
                "backgrounds/14jian.night.png",
            )
            manifest.rimeFiles shouldBe listOf(
                "rime/default.yaml",
                "rime/cn_dicts/base.dict.yaml",
            )
        }

        "missing schema_id fails validation" {
            shouldThrow<IllegalArgumentException> {
                SchemaLayoutManifest.parse(
                    """
                    name: test
                    version: "1"
                    schema_file: test.schema.yaml
                    layout_files: [test.layout.yaml]
                    """.trimIndent(),
                )
            }
        }

        "empty layout_files fails validation" {
            shouldThrow<IllegalArgumentException> {
                SchemaLayoutManifest.parse(
                    """
                    schema_id: test
                    name: test
                    version: "1"
                    schema_file: test.schema.yaml
                    layout_files: []
                    """.trimIndent(),
                )
            }
        }

        "rime_files entries outside rime/ fail validation" {
            shouldThrow<IllegalArgumentException> {
                SchemaLayoutManifest.parse(
                    """
                    schema_id: test
                    name: test
                    version: "1"
                    schema_file: test.schema.yaml
                    layout_files: [test.layout.yaml]
                    rime_files:
                      - default.yaml
                    """.trimIndent(),
                )
            }
        }

        "registry exposes explicit default keyboards by schema id" {
            val registry =
                SchemaLayoutRegistry.fromManifests(
                    SchemaLayoutManifest.parse(
                        """
                        schema_id: 14jian
                        name: 14键
                        version: "1"
                        schema_file: 14jian.schema.yaml
                        layout_files: [14jian.layout.yaml]
                        default_keyboard: 14jian
                        """.trimIndent(),
                    ),
                )

            registry.defaultKeyboardFor("14jian") shouldBe "14jian"
            registry.defaultKeyboardFor("luna_pinyin") shouldBe null
            registry.defaultKeyboards shouldBe mapOf("14jian" to "14jian")
        }
    })
