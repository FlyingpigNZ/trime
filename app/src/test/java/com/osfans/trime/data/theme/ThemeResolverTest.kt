// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

class ThemeResolverTest :
    StringSpec({
        "theme with declarations inherits selected standard components" {
            val theme =
                ThemeResolver.resolve(
                    mapping(
                        """
                        name: test
                        style: {}
                        use_standard_preset_keys: true
                        standard_keyboards: [qwerty]
                        standard_color_schemes: [default]
                        """.trimIndent(),
                    ),
                    standardCatalog(),
                )

            theme.presetKeys.isNotEmpty() shouldBe true
            theme.presetKeyboards.keys.toList() shouldContainExactly listOf("qwerty")
            theme.presetKeyboards.getValue("qwerty").name shouldBe "QWERTY"
            theme.colorSchemes.map { it.id } shouldContainExactly listOf("default")
        }

        "theme without declarations does not inherit standard components" {
            val theme =
                ThemeResolver.resolve(
                    mapping(
                        """
                        name: test
                        style: {}
                        """.trimIndent(),
                    ),
                    standardCatalog(),
                )

            theme.presetKeys shouldBe emptyMap()
            theme.presetKeyboards shouldBe emptyMap()
            theme.colorSchemes shouldBe emptyList()
        }

        "theme keyboard overrides standard keyboard by name" {
            val theme =
                ThemeResolver.resolve(
                    mapping(
                        """
                        name: test
                        style: {}
                        standard_keyboards: [qwerty]
                        preset_keyboards:
                          qwerty:
                            name: Custom QWERTY
                            columns: 5
                        """.trimIndent(),
                    ),
                    standardCatalog(),
                )

            val keyboard = theme.presetKeyboards.getValue("qwerty")
            keyboard.name shouldBe "Custom QWERTY"
            keyboard.columns shouldBe 5
        }

        "theme adds new keyboards while keeping selected standard keyboards" {
            val theme =
                ThemeResolver.resolve(
                    mapping(
                        """
                        name: test
                        style: {}
                        standard_keyboards: [qwerty]
                        preset_keyboards:
                          t9:
                            name: T9
                            columns: 3
                        """.trimIndent(),
                    ),
                    standardCatalog(),
                )

            theme.presetKeyboards.keys.toList() shouldContainExactly listOf("qwerty", "t9")
            theme.presetKeyboards.getValue("qwerty").name shouldBe "QWERTY"
            theme.presetKeyboards.getValue("t9").name shouldBe "T9"
        }

        "theme color scheme overrides standard color scheme by name" {
            val theme =
                ThemeResolver.resolve(
                    mapping(
                        """
                        name: test
                        style: {}
                        standard_color_schemes: [default]
                        preset_color_schemes:
                          default:
                            back_color: '#112233'
                        """.trimIndent(),
                    ),
                    standardCatalog(),
                )

            theme.colorSchemes.single { it.id == "default" }.colors["back_color"] shouldBe "#112233"
        }

        "selecting a standard keyboard pulls in its __include dependencies" {
            val standard =
                StandardCatalog(
                    presetKeys = Node.Mapping(),
                    presetColorSchemes = Node.Mapping(),
                    presetKeyboards =
                        section(
                            """
                            preset_keyboards:
                              qwerty:
                                name: QWERTY
                                columns: 10
                              letter:
                                __include: /preset_keyboards/qwerty
                                ascii_mode: 1
                            """.trimIndent(),
                        ),
                )
            val theme =
                ThemeResolver.resolve(
                    mapping(
                        """
                        name: test
                        style: {}
                        standard_keyboards: [letter]
                        """.trimIndent(),
                    ),
                    standard,
                )

            theme.presetKeyboards.keys.toList() shouldContainExactly listOf("qwerty", "letter")
            theme.presetKeyboards.getValue("letter").name shouldBe "QWERTY"
            theme.presetKeyboards.getValue("letter").asciiMode shouldBe true
        }

        "unknown standard keyboard declaration fails validation" {
            shouldThrow<IllegalArgumentException> {
                ThemeResolver.resolve(
                    mapping(
                        """
                        name: test
                        style: {}
                        standard_keyboards: [does_not_exist]
                        """.trimIndent(),
                    ),
                    standardCatalog(),
                )
            }
        }

        "unknown standard color scheme declaration fails validation" {
            shouldThrow<IllegalArgumentException> {
                ThemeResolver.resolve(
                    mapping(
                        """
                        name: test
                        style: {}
                        standard_color_schemes: [does_not_exist]
                        """.trimIndent(),
                    ),
                    standardCatalog(),
                )
            }
        }

        "shipped decoration-only trime.yaml resolves with shipped standard catalog" {
            val themeNode = mapping(File("src/main/assets/shared/trime.yaml").readText())
            val theme = ThemeResolver.resolve(themeNode, shippedStandard())

            theme.name shouldBe "預設"
            theme.presetKeys.isNotEmpty() shouldBe true
            theme.presetKeyboards.isNotEmpty() shouldBe true
            theme.presetKeyboards.keys shouldContain "default"
            theme.colorSchemes.isNotEmpty() shouldBe true
            theme.colorSchemes.map { it.id } shouldContain "default"
        }

        "minimal 14jian layout resolves with shipped standard catalog" {
            val themeNode =
                mapping(File("../sample_theme_schemas/minimal-14jian/14jian.layout.yaml").readText())
            val theme = ThemeResolver.resolve(themeNode, shippedStandard())

            theme.name shouldBe "14键布局"
            theme.presetKeyboards.keys shouldContain "14jian"
            theme.presetKeyboards.keys shouldContain "letter_14jian"
            theme.presetKeyboards.keys shouldContain "default"
            theme.presetKeys.keys shouldContain "14keyqw"
            theme.presetKeyboards.getValue("14jian").name shouldBe "14键"
            theme.presetKeyboards.getValue("letter_14jian").asciiMode shouldBe true
        }
    })

private fun shippedStandard(): StandardCatalog =
    StandardCatalog(
        presetKeys = section(File("src/main/assets/shared/standard/preset_keys.yaml").readText()),
        presetKeyboards = section(File("src/main/assets/shared/standard/keyboards.yaml").readText()),
        presetColorSchemes = section(File("src/main/assets/shared/standard/colors.yaml").readText()),
    )

private fun standardCatalog(): StandardCatalog =
    StandardCatalog(
        presetKeys =
            section(
                """
                preset_keys:
                  BackSpace: {label: 退格, send: BackSpace}
                """.trimIndent(),
            ),
        presetKeyboards =
            section(
                """
                preset_keyboards:
                  qwerty:
                    name: QWERTY
                    columns: 10
                """.trimIndent(),
            ),
        presetColorSchemes =
            section(
                """
                preset_color_schemes:
                  default:
                    back_color: '#ffffff'
                """.trimIndent(),
            ),
    )

private fun mapping(yaml: String): Node.Mapping {
    val node = Yaml.Default.parseToYamlNode(yaml)
    require(node is Node.Mapping) { "expected a YAML mapping" }
    return node
}

private fun section(yaml: String): Node.Mapping {
    val root = mapping(yaml)
    require(root.pairs.size == 1) { "expected a single top-level section" }
    return root.pairs.values.single() as Node.Mapping
}
