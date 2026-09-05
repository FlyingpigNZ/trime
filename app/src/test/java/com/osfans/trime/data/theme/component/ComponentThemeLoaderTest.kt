// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
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
            val theme = ComponentThemeLoader.loadTheme(manifest)
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
                    name: Legacy Theme
                    style: {}
                    """.trimIndent(),
                )
            ComponentThemeLoader.isComponentManifest(manifest) shouldBe false
        }

        "loads the real split 14jian package style" {
            val manifest = File("../sample_theme_schemas/简纯+14键/manifest.yaml")
            if (manifest.isFile) {
                val theme = ComponentThemeLoader.loadTheme(manifest)
                theme.generalStyle.keyboardHeight shouldBe 240
                theme.generalStyle.keyWidth shouldBe 12
                theme.generalStyle.keyHeight shouldBe 50
                theme.generalStyle.horizontalGap shouldBe 0
                theme.generalStyle.verticalGap shouldBe 0
                theme.generalStyle.keyboardPadding shouldBe 3
                theme.presetKeyboards.keys shouldContain "14jian"
            }
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
                    ComponentThemeLoader.loadTheme(manifest)
                }
            error.message shouldContain "tool_bar.primary_button.background.normal: invalid color '0'"
        }
    })
