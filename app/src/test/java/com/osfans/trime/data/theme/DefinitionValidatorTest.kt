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
        "valid component manifest passes" {
            DefinitionValidator.validateComponentManifest(
                """
                name: test
                components:
                  - keyboard:
                      add:
                        qwerty:
                          name: QWERTY
                """.trimIndent(),
            ).shouldBeEmpty()
        }

        "component manifest missing name fails" {
            val errors =
                DefinitionValidator.validateComponentManifest(
                    """
                    components: []
                    """.trimIndent(),
                )

            errors shouldBe listOf("Component manifest missing 'name'")
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
                    "local-aux/keyboard.yaml" to
                        mapping(
                            """
                            preset_keyboards:
                              default:
                                name: default
                            """.trimIndent(),
                        ),
                    "local-aux/behavior.yaml" to
                        mapping(
                            """
                            preset_keys:
                              BackSpace:
                                send: BackSpace
                            """.trimIndent(),
                        ),
                )
            DefinitionValidator.validateComponentManifest(
                """
                name: test
                components: [local-aux]
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

private fun mapping(yaml: String): Node.Mapping {
    val root = Yaml.Default.parseToYamlNode(yaml)
    require(root is Node.Mapping) { "expected a mapping" }
    return root
}
