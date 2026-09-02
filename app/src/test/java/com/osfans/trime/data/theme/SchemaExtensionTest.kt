// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SchemaExtensionTest :
    StringSpec({

        fun toolbar(yaml: String): ToolBar = ToolBar.decode(
            Yaml.Default.parseToYamlNode(yaml.trimIndent()).mapping!!["tool_bar"]!!.mapping,
        )

        "decodeExtended parses __replace" {
            val ext = Yaml.Default.parseToYamlNode(
                """
                schema_id: wanxiang_t9
                tool_bar:
                  __replace: true
                  button_spacing: 9
                """.trimIndent(),
            ).mapping!!
            val tb = ToolBar.decodeExtended(ext["tool_bar"]?.mapping)!!
            tb.replace shouldBe true
            tb.buttonSpacing shouldBe 9
        }

        "decodeExtended defaults __replace to false" {
            val ext = Yaml.Default.parseToYamlNode(
                """
                tool_bar:
                  button_spacing: 9
                """.trimIndent(),
            ).mapping!!
            val tb = ToolBar.decodeExtended(ext["tool_bar"]?.mapping)!!
            tb.replace shouldBe false
        }

        "malformed __replace throws" {
            val ext = Yaml.Default.parseToYamlNode(
                """
                tool_bar:
                  __replace: not-a-bool
                """.trimIndent(),
            ).mapping!!
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                ToolBar.decodeExtended(ext["tool_bar"]?.mapping)
            }
        }

        "merge: overlay leaf wins, base leaf kept when overlay absent" {
            val base = toolbar(
                """
                tool_bar:
                  button_spacing: 5
                  back_style: ic@arrow-left
                  primary_button:
                    background:
                      type: circle
                      normal: '#ffffff'
                    foreground:
                      style: ic@dots_horizontal
                      normal: candidate_text_color
                    action: menu_keyboard
                """.trimIndent(),
            )
            val overlayNode = Yaml.Default.parseToYamlNode(
                """
                tool_bar:
                  button_spacing: 8
                  primary_button:
                    background:
                      normal: '#000000'
                """.trimIndent(),
            ).mapping!!["tool_bar"]!!.mapping!!
            val merged = SchemaExtensionResolver.mergeToolBarNode(base, overlayNode)
            merged.buttonSpacing shouldBe 8
            merged.backStyle shouldBe "ic@arrow-left"
            merged.primaryButton!!.background.normal shouldBe "#000000"
            // Node-level merge: type not declared in overlay -> base CIRCLE survives.
            merged.primaryButton!!.background.type shouldBe ToolBar.Button.Background.Type.CIRCLE
            merged.primaryButton!!.foreground.style shouldBe "ic@dots_horizontal"
            merged.primaryButton!!.action shouldBe "menu_keyboard"
        }

        "merge: empty overlay buttons keep base buttons" {
            val base = toolbar(
                """
                tool_bar:
                  buttons:
                  - action: Hide
                """.trimIndent(),
            )
            val overlayNode = Yaml.Default.parseToYamlNode(
                """
                tool_bar:
                  button_spacing: 3
                """.trimIndent(),
            ).mapping!!["tool_bar"]!!.mapping!!
            val merged = SchemaExtensionResolver.mergeToolBarNode(base, overlayNode)
            merged.buttons.map { it.action } shouldBe listOf("Hide")
            merged.buttonSpacing shouldBe 3
        }

        "merge: overlay absent buttons keep base button fields (no field loss)" {
            // Regression for the baseNode() round-trip dropping button fields:
            // when the overlay does not declare `buttons`, the base `buttons`
            // list must survive the node re-serialization without losing
            // option_styles / size / long_press_action / padding / font_size /
            // insets / corner_radius (see CODE_REVIEW P1).
            val base = toolbar(
                """
                tool_bar:
                  buttons:
                  - action: Hide
                    long_press_action: Menu
                    size: [18, 24]
                    background:
                      type: circle
                      corner_radius: 6
                      normal: '#ffffff'
                      highlight: '#000000'
                      vertical_inset: 3
                      horizontal_inset: 5
                    foreground:
                      style: ic@dots_horizontal
                      option_styles: [ic@moon_full, ic@moon_new]
                      normal: key_text_color
                      highlight: key_symbol_color
                      font_size: 21
                      padding: 9
                  - action: Settings
                """.trimIndent(),
            )
            val overlayNode = Yaml.Default.parseToYamlNode(
                """
                tool_bar:
                  button_spacing: 8
                """.trimIndent(),
            ).mapping!!["tool_bar"]!!.mapping!!
            val merged = SchemaExtensionResolver.mergeToolBarNode(base, overlayNode)
            val first = merged.buttons.first()
            first.action shouldBe "Hide"
            first.longPressAction shouldBe "Menu"
            first.size shouldBe listOf(18, 24)
            first.background.type shouldBe ToolBar.Button.Background.Type.CIRCLE
            first.background.cornerRadius shouldBe 6f
            first.background.normal shouldBe "#ffffff"
            first.background.highlight shouldBe "#000000"
            first.background.verticalInset shouldBe 3
            first.background.horizontalInset shouldBe 5
            first.foreground.style shouldBe "ic@dots_horizontal"
            first.foreground.optionStyles shouldBe listOf("ic@moon_full", "ic@moon_new")
            first.foreground.normal shouldBe "key_text_color"
            first.foreground.highlight shouldBe "key_symbol_color"
            first.foreground.fontSize shouldBe 21f
            first.foreground.padding shouldBe 9
            // The second base button survives too.
            merged.buttons.map { it.action } shouldBe listOf("Hide", "Settings")
        }

        "merge: overlay buttons replace base buttons" {
            val base = toolbar(
                """
                tool_bar:
                  buttons:
                  - action: Hide
                """.trimIndent(),
            )
            val overlayNode = Yaml.Default.parseToYamlNode(
                """
                tool_bar:
                  buttons:
                  - action: Settings
                  - action: Emoji
                """.trimIndent(),
            ).mapping!!["tool_bar"]!!.mapping!!
            val merged = SchemaExtensionResolver.mergeToolBarNode(base, overlayNode)
            merged.buttons.map { it.action } shouldBe listOf("Settings", "Emoji")
        }

        "replace: overlay replaces entirely" {
            val base = toolbar(
                """
                tool_bar:
                  button_spacing: 5
                  back_style: ic@arrow-left
                  buttons:
                  - action: Hide
                """.trimIndent(),
            )
            val overlay = toolbar(
                """
                tool_bar:
                  button_spacing: 9
                """.trimIndent(),
            ).copy(replace = true)
            // replace semantics: the overlay replaces the base toolbar.
            val result = SchemaExtensionResolver.resolveToolBar(base, overlay)
            result.buttonSpacing shouldBe 9
            result.buttons shouldBe emptyList()
            result.backStyle shouldBe "ic@arrow-left"
        }

        "resolveToolBar with schema file replaces when __replace: true" {
            val workspace = java.io.File.createTempFile("ws-test", "").apply {
                delete()
                mkdirs()
            }
            java.io.File(workspace, "wanxiang_t9.extended.yaml").writeText(
                """
                schema_id: wanxiang_t9
                tool_bar:
                  __replace: true
                  button_spacing: 12
                """.trimIndent(),
            )
            val base = toolbar(
                """
                tool_bar:
                  button_spacing: 5
                  buttons:
                  - action: Hide
                """.trimIndent(),
            )
            val result = SchemaExtensionResolver.resolveToolBar(base, "wanxiang_t9", workspace)
            result.buttonSpacing shouldBe 12
            result.buttons shouldBe emptyList()
            workspace.deleteRecursively()
        }

        "resolveToolBar with schema file merges by default" {
            val workspace = java.io.File.createTempFile("ws-test", "").apply {
                delete()
                mkdirs()
            }
            java.io.File(workspace, "wanxiang_t9.extended.yaml").writeText(
                """
                schema_id: wanxiang_t9
                tool_bar:
                  button_spacing: 12
                """.trimIndent(),
            )
            val base = toolbar(
                """
                tool_bar:
                  button_spacing: 5
                  buttons:
                  - action: Hide
                """.trimIndent(),
            )
            val result = SchemaExtensionResolver.resolveToolBar(base, "wanxiang_t9", workspace)
            result.buttonSpacing shouldBe 12
            result.buttons.map { it.action } shouldBe listOf("Hide")
            workspace.deleteRecursively()
        }

        "resolveToolBar without schema file keeps base" {
            val workspace = java.io.File.createTempFile("ws-test", "").apply {
                delete()
                mkdirs()
            }
            val base = toolbar(
                """
                tool_bar:
                  button_spacing: 5
                """.trimIndent(),
            )
            val result = SchemaExtensionResolver.resolveToolBar(base, "no_such_schema", workspace)
            result shouldBe base
            workspace.deleteRecursively()
        }

        "Theme tool_bar override merges by default via node-level resolve" {
            val theme = Theme.decode(
                Node.Mapping(
                    linkedMapOf(
                        Node.Scalar("name") to Node.Scalar("test"),
                        Node.Scalar("style") to Node.Mapping(),
                        Node.Scalar("tool_bar") to Yaml.Default.parseToYamlNode(
                            """
                            button_spacing: 5
                            back_style: ic@arrow-left
                            """.trimIndent(),
                        ).mapping!!,
                    ),
                ),
            )
            val overlayNode = Yaml.Default.parseToYamlNode(
                """
                tool_bar:
                  button_spacing: 11
                """.trimIndent(),
            ).mapping!!["tool_bar"]!!.mapping!!
            val merged = SchemaExtensionResolver.mergeToolBarNode(theme.toolBar, overlayNode)
            merged.buttonSpacing shouldBe 11
            merged.backStyle shouldBe "ic@arrow-left"
        }

        "SchemaExtension.load parses tool_bar and t9_disambiguation" {
            val file = java.io.File.createTempFile("ext", ".yaml")
            file.writeText(
                """
                schema_id: wanxiang_t9
                tool_bar:
                  button_spacing: 7
                t9_disambiguation:
                  enabled: true
                  input_method: full
                  keyboard: wanxiang_t9
                  syllables:
                  - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42'}
                """.trimIndent(),
            )
            val ext = SchemaExtension.load(file)!!
            ext.schemaId shouldBe "wanxiang_t9"
            ext.toolBar!!.buttonSpacing shouldBe 7
            ext.t9Disambiguation!!.enabled shouldBe true
            ext.t9Disambiguation!!.keyboard shouldBe "wanxiang_t9"
            ext.t9Disambiguation!!.syllables.single().pinyin shouldBe "hao"
            file.delete()
        }

        "t9_disambiguation.keyboard defaults to null when absent" {
            val file = java.io.File.createTempFile("ext", ".yaml")
            file.writeText(
                """
                schema_id: wanxiang_t9
                t9_disambiguation:
                  enabled: true
                  input_method: full
                  syllables:
                  - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42'}
                """.trimIndent(),
            )
            val ext = SchemaExtension.load(file)!!
            ext.t9Disambiguation!!.keyboard shouldBe null
            file.delete()
        }

        "SchemaExtension.load returns null when file absent" {
            SchemaExtension.load(java.io.File("/nonexistent/x.extended.yaml")) shouldBe null
        }

        "SchemaExtension.load throws on malformed __replace" {
            val file = java.io.File.createTempFile("ext", ".yaml")
            file.writeText(
                """
                tool_bar:
                  __replace: yes
                """.trimIndent(),
            )
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                SchemaExtension.load(file)
            }
            file.delete()
        }

        "SchemaExtension.validate collects structural errors" {
            val errors = SchemaExtension.validate(
                """
                tool_bar:
                  __replace: not-a-bool
                t9_disambiguation:
                  input_method: sogou
                """.trimIndent(),
                "test.extended.yaml",
            )
            errors.any { it.contains("__replace must be a boolean") } shouldBe true
            errors.any { it.contains("input_method must be 'full' or 'flypy'") } shouldBe true
        }

        "SchemaExtension.validate accepts a valid file" {
            SchemaExtension.validate(
                """
                schema_id: wanxiang_t9
                tool_bar:
                  __replace: false
                t9_disambiguation:
                  input_method: full
                """.trimIndent(),
                "test.extended.yaml",
            ) shouldBe emptyList()
        }

        "DefinitionValidator.validateExtendedFiles validates workspace files" {
            val workspace = java.io.File.createTempFile("ws-test", "").apply {
                delete()
                mkdirs()
            }
            java.io.File(workspace, "wanxiang_t9.extended.yaml").writeText(
                """
                schema_id: wanxiang_t9
                tool_bar:
                  __replace: false
                """.trimIndent(),
            )
            java.io.File(workspace, "broken.extended.yaml").writeText(
                """
                tool_bar:
                  __replace: nope
                """.trimIndent(),
            )
            val errors = DefinitionValidator.validateExtendedFiles(workspace)
            errors.any { it.contains("broken.extended.yaml") } shouldBe true
            errors.any { it.contains("wanxiang_t9.extended.yaml") } shouldBe false
            workspace.deleteRecursively()
        }

        "DefinitionValidator.validateExtendedFiles flags schema_id/file-name mismatch" {
            val workspace = java.io.File.createTempFile("ws-test", "").apply {
                delete()
                mkdirs()
            }
            java.io.File(workspace, "wanxiang_flypy_t9.extended.yaml").writeText(
                """
                schema_id: wanxiang_t9
                tool_bar:
                  __replace: false
                """.trimIndent(),
            )
            val errors = DefinitionValidator.validateExtendedFiles(workspace)
            errors.any {
                it.contains("wanxiang_flypy_t9.extended.yaml") &&
                    it.contains("schema_id 'wanxiang_t9' does not match file name 'wanxiang_flypy_t9'")
            } shouldBe true
            workspace.deleteRecursively()
        }
    })
