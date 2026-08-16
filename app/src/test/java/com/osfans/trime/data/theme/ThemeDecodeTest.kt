// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ThemeDecodeTest :
    StringSpec({
        "minimal theme decodes with defaults" {
            val theme = decode(
                """
                name: test
                style: {}
                """.trimIndent(),
            )

            theme.name shouldBe "test"
            theme.presetKeyboards shouldBe emptyMap()
            theme.colorSchemes shouldBe emptyList()
            theme.generalStyle.autoCaps shouldBe false
            theme.generalStyle.candidateFont shouldBe emptyList()
        }

        "self-contained color scheme pair decodes light and dark palettes" {
            val theme = decode(
                """
                name: test
                style: {}
                preset_color_schemes:
                  default:
                    light:
                      name: 默认
                      back_color: '#ffffff'
                      text_color: '#000000'
                    dark:
                      name: 默认
                      back_color: '#1e1e1e'
                      text_color: '#e0e0e0'
                """.trimIndent(),
            )

            val scheme = theme.colorSchemes.single()
            scheme.id shouldBe "default"
            scheme.displayName shouldBe "默认"
            scheme.colors["back_color"] shouldBe "#ffffff"
            scheme.colors["text_color"] shouldBe "#000000"
            scheme.darkColors["back_color"] shouldBe "#1e1e1e"
            scheme.darkColors["text_color"] shouldBe "#e0e0e0"
        }

        "legacy flat color scheme uses itself as dark fallback" {
            val theme = decode(
                """
                name: test
                style: {}
                preset_color_schemes:
                  default:
                    name: 默认
                    back_color: '#ffffff'
                """.trimIndent(),
            )

            val scheme = theme.colorSchemes.single()
            scheme.colors["back_color"] shouldBe "#ffffff"
            scheme.darkColors["back_color"] shouldBe "#ffffff"
        }

        "__include inherits base keyboard and child overrides win" {
            val theme = decode(
                """
                name: test
                style: {}
                preset_keyboards:
                  base:
                    name: base
                    columns: 10
                    keys:
                      - label: a
                  child:
                    __include: /preset_keyboards/base
                    columns: 12
                """.trimIndent(),
            )

            theme.presetKeyboards.keys.toList() shouldContainExactly listOf("base", "child")
            val child = theme.presetKeyboards.getValue("child")
            child.name shouldBe "base"
            child.columns shouldBe 12
            child.keys.size shouldBe 1
            child.keys.first().label shouldBe "a"
        }

        "legacy import_preset is retired and no longer inherits" {
            val theme = decode(
                """
                name: test
                style: {}
                preset_keyboards:
                  base:
                    name: base
                    columns: 7
                  child:
                    import_preset: base
                    columns: 8
                """.trimIndent(),
            )

            val child = theme.presetKeyboards.getValue("child")
            child.name shouldBe ""
            child.columns shouldBe 8
        }

        "circular __include is detected" {
            val yaml =
                """
                name: test
                style: {}
                preset_keyboards:
                  a:
                    __include: /preset_keyboards/b
                  b:
                    __include: /preset_keyboards/a
                """.trimIndent()

            val exception =
                shouldThrow<IllegalArgumentException> {
                    decode(yaml)
                }
            exception.message shouldBe
                "Circular __include in preset_keyboards: a -> b"
        }

        "missing __include base fails loudly instead of silently dropping" {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    decode(
                        """
                        name: test
                        style: {}
                        preset_keyboards:
                          child:
                            __include: /preset_keyboards/does_not_exist
                            columns: 9
                        """.trimIndent(),
                    )
                }
            exception.message shouldBe
                "Unknown __include target '/preset_keyboards/does_not_exist' in preset_keyboards entry 'child'"
        }
    })

private fun decode(yaml: String): Theme {
    val node = Yaml.Default.parseToYamlNode(yaml)
    require(node is Node.Mapping) { "expected a YAML mapping" }
    return Theme.decode(node)
}
