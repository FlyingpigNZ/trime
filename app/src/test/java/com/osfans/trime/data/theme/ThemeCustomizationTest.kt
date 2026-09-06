// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class ThemeCustomizationTest :
    StringSpec({
        val pairedSchemeBody =
            """
            default/dark_temple:
              name: 简白 / 暗堂
              light:
                back_color: '0xffffffff'
                keyboard_background: default.jpg
              dark:
                back_color: '0xff222222'
                keyboard_background: dark_temple.jpg
            """.trimIndent()

        "per-mode override touches only the targeted palette copy" {
            val merged = customize(pairedSchemeBody, "dark", "custom_default_dark_temple_night.png")
            val scheme = schemeOf(merged, "default/dark_temple")

            scheme.mode("dark").scalar("keyboard_background") shouldBe "custom_default_dark_temple_night.png"
            scheme.mode("light").scalar("keyboard_background") shouldBe "default.jpg"
        }

        "schemes sharing the same palette content stay isolated" {
            val body =
                """
                pair_a:
                  light:
                    back_color: '0xffffffff'
                    keyboard_background: shared_day.jpg
                  dark:
                    back_color: '0xff222222'
                    keyboard_background: shared_night.jpg
                pair_b:
                  light:
                    back_color: '0xffffffff'
                    keyboard_background: shared_day.jpg
                  dark:
                    back_color: '0xff222222'
                    keyboard_background: shared_night.jpg
                """.trimIndent()
            val customization =
                mapping(
                    """
                    color_schemes:
                      pair_a:
                        dark:
                          keyboard_background: custom_pair_a_night.png
                    """.trimIndent(),
                )
            val merged = ThemeCustomization.applyColorSchemeOverrides(schemeSections(body), customization)

            schemeOf(merged, "pair_b").mode("dark").scalar("keyboard_background") shouldBe "shared_night.jpg"
            schemeOf(merged, "pair_a").mode("light").scalar("keyboard_background") shouldBe "shared_day.jpg"
        }

        "flat legacy scheme gains a dark palette copy for a dark-only override" {
            val body =
                """
                legacy:
                  name: Legacy
                  back_color: '0xff111111'
                  text_color: '0xffeeeeee'
                """.trimIndent()
            val customization =
                mapping(
                    """
                    color_schemes:
                      legacy:
                        dark:
                          keyboard_background: custom_legacy_dark.png
                    """.trimIndent(),
                )
            val merged = ThemeCustomization.applyColorSchemeOverrides(schemeSections(body), customization)
            val scheme = schemeOf(merged, "legacy")

            scheme.scalar("name") shouldBe "Legacy"
            scheme.mode("light").scalar("back_color") shouldBe "0xff111111"
            scheme.mode("light").scalar("keyboard_background") shouldBe null
            scheme.mode("dark").scalar("back_color") shouldBe "0xff111111"
            scheme.mode("dark").scalar("keyboard_background") shouldBe "custom_legacy_dark.png"
        }

        "scheme without explicit dark gets a dark copy of light before dark override" {
            val body =
                """
                plain:
                  name: Plain
                  light:
                    back_color: '0xffffffff'
                    text_color: '0xff000000'
                """.trimIndent()
            val customization =
                mapping(
                    """
                    color_schemes:
                      plain:
                        dark:
                          keyboard_background: custom_plain_dark.png
                    """.trimIndent(),
                )
            val merged = ThemeCustomization.applyColorSchemeOverrides(schemeSections(body), customization)
            val dark = schemeOf(merged, "plain").mode("dark")

            dark.scalar("back_color") shouldBe "0xffffffff"
            dark.scalar("text_color") shouldBe "0xff000000"
            dark.scalar("keyboard_background") shouldBe "custom_plain_dark.png"
        }

        "valid customization passes validation" {
            val customization =
                mapping(
                    """
                    color_schemes:
                      default/dark_temple:
                        dark:
                          keyboard_background: custom_night.png
                        light:
                          back_color: '0xff000000'
                    """.trimIndent(),
                )
            DefinitionValidator.validateCustomization(schemeSections(pairedSchemeBody), customization).shouldBeEmpty()
        }

        "scheme without keyboard_background may still gain one" {
            val body =
                """
                plain:
                  name: Plain
                  light:
                    back_color: '0xffffffff'
                """.trimIndent()
            val customization =
                mapping(
                    """
                    color_schemes:
                      plain:
                        light:
                          keyboard_background: custom_plain_day.png
                    """.trimIndent(),
                )
            DefinitionValidator.validateCustomization(schemeSections(body), customization).shouldBeEmpty()
        }

        "unknown scheme id fails validation" {
            val customization =
                mapping(
                    """
                    color_schemes:
                      nope:
                        dark:
                          keyboard_background: x.png
                    """.trimIndent(),
                )
            val errors = DefinitionValidator.validateCustomization(schemeSections(pairedSchemeBody), customization)
            errors.shouldContain("customization.yaml: unknown color scheme 'nope'")
        }

        "unknown mode fails validation" {
            val customization =
                mapping(
                    """
                    color_schemes:
                      default/dark_temple:
                        night:
                          keyboard_background: x.png
                    """.trimIndent(),
                )
            val errors = DefinitionValidator.validateCustomization(schemeSections(pairedSchemeBody), customization)
            errors.shouldContain(
                "customization.yaml: color_schemes.default/dark_temple: mode must be 'light' or 'dark'",
            )
        }

        "unknown key fails validation" {
            val customization =
                mapping(
                    """
                    color_schemes:
                      default/dark_temple:
                        dark:
                          key_board_background: x.png
                    """.trimIndent(),
                )
            val errors = DefinitionValidator.validateCustomization(schemeSections(pairedSchemeBody), customization)
            errors.shouldContain(
                "customization.yaml: color_schemes.default/dark_temple.dark: unknown key 'key_board_background'",
            )
        }

        "non-drawable key with non-hex value fails validation" {
            val customization =
                mapping(
                    """
                    color_schemes:
                      default/dark_temple:
                        dark:
                          back_color: not-a-color
                    """.trimIndent(),
                )
            val errors = DefinitionValidator.validateCustomization(schemeSections(pairedSchemeBody), customization)
            errors.shouldContain(
                "customization.yaml: color_schemes.default/dark_temple.dark.back_color: invalid color 'not-a-color'",
            )
        }

        "unknown root key fails validation" {
            val customization =
                mapping(
                    """
                    style:
                      keyboard_height: 300
                    """.trimIndent(),
                )
            val errors = DefinitionValidator.validateCustomization(schemeSections(pairedSchemeBody), customization)
            errors.shouldContain("customization.yaml: unknown key 'style'")
        }

        "empty customization passes validation and changes nothing" {
            DefinitionValidator.validateCustomization(schemeSections(pairedSchemeBody), Node.Mapping()).shouldBeEmpty()
            ThemeCustomization.applyColorSchemeOverrides(schemeSections(pairedSchemeBody), Node.Mapping()) shouldBe
                schemeSections(pairedSchemeBody)
        }
    })

private fun customize(
    body: String,
    mode: String,
    value: String,
): Map<String, Node.Mapping> {
    val schemeId = body.substringBefore(':').trim()
    val customization =
        mapping(
            """
            color_schemes:
              $schemeId:
                $mode:
                  keyboard_background: $value
            """.trimIndent(),
        )
    return ThemeCustomization.applyColorSchemeOverrides(schemeSections(body), customization)
}

private fun schemeSections(body: String): Map<String, Node.Mapping> {
    val indented = body.lines().joinToString("\n") { line -> if (line.isBlank()) line else "  $line" }
    val root = mapping("preset_color_schemes:\n$indented")
    val preset = root[Node.Scalar("preset_color_schemes")] as? Node.Mapping
        ?: error("preset_color_schemes is not a mapping")
    return mapOf("preset_color_schemes" to preset)
}

private fun schemeOf(
    sections: Map<String, Node.Mapping>,
    id: String,
): Node.Mapping = sections.getValue("preset_color_schemes")[Node.Scalar(id)] as? Node.Mapping
    ?: error("No such scheme: $id")

private fun Node.Mapping.mode(name: String): Node.Mapping = this[Node.Scalar(name)] as? Node.Mapping
    ?: error("No such mode: $name")

private fun Node.Mapping.scalar(key: String): String? = (this[Node.Scalar(key)] as? Node.Scalar)?.string

private fun mapping(yaml: String): Node.Mapping = Yaml.Default.parseToYamlNode(yaml.trimIndent()) as? Node.Mapping
    ?: error("Not a YAML mapping")
