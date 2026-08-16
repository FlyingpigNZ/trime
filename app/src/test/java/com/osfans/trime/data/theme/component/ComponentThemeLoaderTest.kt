// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
    })
