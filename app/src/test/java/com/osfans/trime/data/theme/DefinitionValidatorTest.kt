// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.component.ComponentSource
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class DefinitionValidatorTest :
    StringSpec({
        "valid theme passes" {
            val errors =
                DefinitionValidator.validateTheme(
                    """
                    name: test
                    style: {}
                    """.trimIndent(),
                    testStandard(),
                )

            errors.shouldBeEmpty()
        }

        "theme with unknown standard keyboard fails" {
            val errors =
                DefinitionValidator.validateTheme(
                    """
                    name: test
                    style: {}
                    standard_keyboards: [does_not_exist]
                    """.trimIndent(),
                    testStandard(),
                )

            errors.shouldContain("Unknown standard_keyboards entries: does_not_exist. These names are not present in the standard catalog.")
        }

        "theme missing name fails" {
            val errors = DefinitionValidator.validateTheme("style: {}", testStandard())

            errors.shouldContain("Missing required field 'name'")
        }

        "valid layout fragment passes" {
            val errors =
                DefinitionValidator.validateLayoutFragment(
                    """
                    standard_keyboards: [qwerty]
                    preset_keyboards:
                      custom: {name: Custom, columns: 4}
                    """.trimIndent(),
                    testStandard(),
                )

            errors.shouldBeEmpty()
        }

        "layout fragment with unknown standard color fails" {
            val errors =
                DefinitionValidator.validateLayoutFragment(
                    """
                    standard_color_schemes: [does_not_exist]
                    """.trimIndent(),
                    testStandard(),
                )

            errors.shouldContain("Unknown standard_color_schemes entries: does_not_exist. These names are not present in the standard catalog.")
        }

        "valid manifest passes" {
            DefinitionValidator.validateManifest(
                """
                schema_id: 14jian
                name: 14键
                version: "1"
                schema_file: 14jian.schema.yaml
                layout_files: [14jian.layout.yaml]
                """.trimIndent(),
            ).shouldBeEmpty()
        }

        "manifest missing schema_id fails" {
            val errors =
                DefinitionValidator.validateManifest(
                    """
                    name: 14键
                    version: "1"
                    schema_file: 14jian.schema.yaml
                    layout_files: [14jian.layout.yaml]
                    """.trimIndent(),
                )

            errors shouldBe listOf("Missing required field 'schema_id'")
        }

        "valid component manifest passes" {
            DefinitionValidator.validateComponentManifest(
                """
                name: test
                components: [standard]
                """.trimIndent(),
            ).shouldBeEmpty()
        }

        "component manifest with duplicate add fails" {
            val errors =
                DefinitionValidator.validateComponentManifest(
                    """
                    name: test
                    components:
                      - keyboard:
                          add:
                            qwerty: {}
                      - keyboard:
                          add:
                            qwerty: {}
                    """.trimIndent(),
                    ComponentSource.fromMap(emptyMap()),
                )

            errors.shouldContain("preset_keyboards.add: 'qwerty' already exists; use override")
        }

        "component manifest resolves against in-memory source" {
            val files =
                mapOf(
                    "standard/preset_keys.yaml" to
                        section(
                            """
                            preset_keys:
                              BackSpace:
                                send: BackSpace
                            """.trimIndent(),
                        ),
                    "standard/keyboards.yaml" to
                        section(
                            """
                            preset_keyboards:
                              default:
                                name: default
                            """.trimIndent(),
                        ),
                    "standard/colors.yaml" to
                        section(
                            """
                            preset_color_schemes:
                              default:
                                light: {}
                            """.trimIndent(),
                        ),
                )
            DefinitionValidator.validateComponentManifest(
                """
                name: test
                components: [standard]
                """.trimIndent(),
                ComponentSource.fromMap(files),
            ).shouldBeEmpty()
        }

        "component manifest with invalid tool_bar color fails" {
            val styleFile =
                Yaml.Default.parseToYamlNode(
                    """
                    tool_bar:
                      primary_button:
                        background:
                          normal: 0
                    """.trimIndent(),
                ).mapping!!
            val errors =
                DefinitionValidator.validateComponentManifest(
                    """
                    name: test
                    components:
                      - style:
                          file: style.yaml
                    """.trimIndent(),
                    ComponentSource.fromMap(mapOf("style.yaml" to styleFile)),
                )
            errors.shouldContain("tool_bar.primary_button.background.normal: invalid color '0'")
        }

        "component manifest with decimal color scheme fails" {
            val colorFile =
                Yaml.Default.parseToYamlNode(
                    """
                    preset_color_schemes:
                      default:
                        light:
                          key_text_color: 4278190080
                    """.trimIndent(),
                ).mapping!!
            val errors =
                DefinitionValidator.validateComponentManifest(
                    """
                    name: test
                    components:
                      - color:
                          file: color.yaml
                    """.trimIndent(),
                    ComponentSource.fromMap(mapOf("color.yaml" to colorFile)),
                )
            errors.shouldContain("preset_color_schemes: invalid color '4278190080' for 'key_text_color'")
        }
    })

private fun testStandard(): StandardCatalog =
    StandardCatalog(
        presetKeys = Node.Mapping(),
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
                    light:
                      back_color: '#ffffff'
                """.trimIndent(),
            ),
    )

private fun section(yaml: String): Node.Mapping {
    val root = Yaml.Default.parseToYamlNode(yaml)
    require(root is Node.Mapping) { "expected a mapping" }
    require(root.pairs.size == 1) { "expected one section" }
    return root.pairs.values.single() as Node.Mapping
}
