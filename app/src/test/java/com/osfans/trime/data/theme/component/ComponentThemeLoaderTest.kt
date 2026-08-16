// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class ComponentThemeLoaderTest :
    StringSpec({
        fun write(root: File, name: String, content: String): File {
            val file = File(root, name)
            file.parentFile?.mkdirs()
            file.writeText(content.trimIndent(), Charsets.UTF_8)
            return file
        }

        "loads a theme from a component manifest without standard catalog" {
            val root = Files.createTempDirectory("component-theme").toFile()
            val manifest =
                write(
                    root,
                    "component.yaml",
                    """
                    name: Test Theme
                    author: tester
                    components:
                      - keyboard:
                          add:
                            qwerty:
                              name: QWERTY
                              columns: 10
                              keys:
                                - {click: a}
                      - behavior:
                          add:
                            BackSpace: {label: 退格, send: BackSpace}
                      - style:
                          override:
                            keyboard_height: 200
                      - color:
                          add:
                            default:
                              light:
                                back_color: '#ffffff'
                    """.trimIndent(),
                )

            ComponentThemeLoader.isComponentManifest(manifest) shouldBe true
            val theme = ComponentThemeLoader.loadTheme(manifest, null)
            theme.name shouldBe "Test Theme"
            theme.generalStyle.keyboardHeight shouldBe 200
            theme.presetKeyboards.keys shouldBe setOf("qwerty")
            theme.presetKeys.keys shouldBe setOf("BackSpace")
            theme.colorSchemes.map { it.id } shouldBe listOf("default")
        }

        "rejects a non-component manifest" {
            val root = Files.createTempDirectory("component-theme").toFile()
            val manifest =
                write(
                    root,
                    "manifest.yaml",
                    """
                    schema_id: 14jian
                    schema_file: 14jian.schema.yaml
                    layout_files: [14jian.layout.yaml]
                    """.trimIndent(),
                )
            ComponentThemeLoader.isComponentManifest(manifest) shouldBe false
        }

        "resolves standard catalog from the fallback shared root" {
            val sharedRoot = Files.createTempDirectory("shared-root").toFile()
            write(
                sharedRoot,
                "standard/preset_keys.yaml",
                """
                preset_keys:
                  BackSpace: {label: 退格, send: BackSpace}
                """.trimIndent(),
            )
            write(
                sharedRoot,
                "standard/keyboards.yaml",
                """
                preset_keyboards:
                  default:
                    name: default
                    keys: []
                """.trimIndent(),
            )
            write(
                sharedRoot,
                "standard/colors.yaml",
                """
                preset_color_schemes:
                  default:
                    light:
                      back_color: '#ffffff'
                """.trimIndent(),
            )

            val themeDir = File(sharedRoot, "tongwenfeng")
            val manifest =
                write(
                    themeDir,
                    "component.yaml",
                    """
                    name: Component Tongwenfeng
                    use_standard_preset_keys: true
                    standard_keyboards: [default]
                    standard_color_schemes: [default]
                    components:
                      - standard
                      - style:
                          override:
                            keyboard_height: 200
                    """.trimIndent(),
                )

            val theme = ComponentThemeLoader.loadTheme(manifest, null, sharedRoot)
            theme.name shouldBe "Component Tongwenfeng"
            theme.presetKeys.keys shouldBe setOf("BackSpace")
            theme.presetKeyboards.keys shouldBe setOf("default")
            theme.colorSchemes.map { it.id } shouldBe listOf("default")
        }

        "rejects invalid color literals during load" {
            val root = Files.createTempDirectory("component-theme-bad-color").toFile()
            write(
                root,
                "style.yaml",
                """
                tool_bar:
                  primary_button:
                    background:
                      normal: 0
                """.trimIndent(),
            )
            val manifest =
                write(
                    root,
                    "component.yaml",
                    """
                    name: Bad Color Theme
                    components:
                      - style:
                          file: style.yaml
                    """.trimIndent(),
                )
            val error =
                shouldThrow<IllegalArgumentException> {
                    ComponentThemeLoader.loadTheme(manifest, null)
                }
            error.message shouldContain "tool_bar.primary_button.background.normal: invalid color '0'"
        }
    })
