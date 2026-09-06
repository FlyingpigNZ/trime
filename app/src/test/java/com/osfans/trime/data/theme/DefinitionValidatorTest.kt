// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain

class DefinitionValidatorTest :
    StringSpec({
        "fallback color cycle is rejected" {
            val sections =
                mapOf(
                    "fallback_colors" to
                        mapping(
                            """
                            a: b
                            b: a
                            """.trimIndent(),
                        ),
                )
            val errors = DefinitionValidator.validateColorLiterals(sections)
            errors.shouldContain("fallback_colors: cycle detected: a -> b -> a")
        }

        "fallback references are order-independent and accept builtin keys" {
            val sections =
                mapOf(
                    "preset_color_schemes" to
                        mapping(
                            """
                            default:
                              light:
                                back_color: '#ffffff'
                            """.trimIndent(),
                        ),
                    "fallback_colors" to
                        mapping(
                            """
                            a: b
                            b: candidate_text_color
                            """.trimIndent(),
                        ),
                )
            DefinitionValidator.validateColorLiterals(sections).shouldBeEmpty()
        }
    })

private fun mapping(yaml: String): Node.Mapping {
    val root = Yaml.Default.parseToYamlNode(yaml)
    require(root is Node.Mapping) { "expected a mapping" }
    return root
}
