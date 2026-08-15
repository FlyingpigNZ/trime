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

        "legacy import_preset alias is still resolved" {
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
            child.name shouldBe "base"
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

        "missing __include base falls back to the including keyboard" {
            val theme = decode(
                """
                name: test
                style: {}
                preset_keyboards:
                  child:
                    __include: /preset_keyboards/does_not_exist
                    columns: 9
                """.trimIndent(),
            )

            theme.presetKeyboards.getValue("child").columns shouldBe 9
        }
    })

private fun decode(yaml: String): Theme {
    val node = Yaml.Default.parseToYamlNode(yaml)
    require(node is Node.Mapping) { "expected a YAML mapping" }
    return Theme.decode(node)
}
