// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ComponentResolverTest :
    StringSpec({
        fun mapping(yaml: String): Node.Mapping =
            Yaml.Default.parseToYamlNode(yaml.trimIndent()).mapping!!

        fun manifest(yaml: String): ComponentManifest =
            ComponentManifest.parse(mapping(yaml))

        fun resolve(
            manifestYaml: String,
            files: Map<String, Node.Mapping> = emptyMap(),
        ): Map<String, Node.Mapping> {
            val resolver = ComponentResolver(ComponentSource.fromMap(files))
            return resolver.resolve(manifest(manifestYaml))
        }

        "add override remove layering" {
            val sections =
                resolve(
                    """
                    name: test
                    components:
                      - keyboard:
                          add:
                            qwerty:
                              name: base
                            symbols:
                              name: base-symbols
                      - keyboard:
                          override:
                            qwerty:
                              name: override
                              width: 10
                          remove: [symbols]
                          add:
                            14jian:
                              name: 14键
                    """.trimIndent(),
                )
            val keyboards = sections.getValue("preset_keyboards")
            keyboards["qwerty"]?.mapping?.get("name")?.string shouldBe "override"
            keyboards["qwerty"]?.mapping?.get("width")?.string shouldBe "10"
            keyboards["symbols"] shouldBe null
            keyboards["14jian"]?.mapping?.get("name")?.string shouldBe "14键"
        }

        "style field override merges" {
            val sections =
                resolve(
                    """
                    name: test
                    components:
                      - style:
                          override:
                            keyboard_height: 200
                            key_height: 40
                      - style:
                          override:
                            key_height: 50
                    """.trimIndent(),
                )
            val style = sections.getValue("style")
            style["keyboard_height"]?.string shouldBe "200"
            style["key_height"]?.string shouldBe "50"
        }

        "add existing fails" {
            shouldThrow<IllegalArgumentException> {
                resolve(
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
                )
            }
        }

        "override missing fails" {
            shouldThrow<IllegalArgumentException> {
                resolve(
                    """
                    name: test
                    components:
                      - keyboard:
                          override:
                            missing: {}
                    """.trimIndent(),
                )
            }
        }

        "remove missing fails" {
            shouldThrow<IllegalArgumentException> {
                resolve(
                    """
                    name: test
                    components:
                      - keyboard:
                          remove: [missing]
                    """.trimIndent(),
                )
            }
        }

        "component directory include" {
            val files =
                mapOf(
                    "local-aux/keyboard.yaml" to
                        mapping(
                            """
                            preset_keyboards:
                              aux1:
                                name: aux1
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
            val sections =
                resolve(
                    """
                    name: test
                    components:
                      - local-aux
                      - keyboard:
                          override:
                            aux1:
                              name: changed
                    """.trimIndent(),
                    files,
                )
            sections.getValue("preset_keyboards")["aux1"]?.mapping?.get("name")?.string shouldBe "changed"
            sections.getValue("preset_keys")["BackSpace"]?.mapping?.get("send")?.string shouldBe "BackSpace"
        }

        "flat colors and color_schemes resolve to preset_color_schemes" {
            val sections =
                resolve(
                    """
                    name: test
                    components:
                      - color:
                          file: color.yaml
                    """.trimIndent(),
                    mapOf(
                        "color.yaml" to
                            mapping(
                                """
                                colors:
                                  A:
                                    back_color: '#ffffff'
                                  B:
                                    back_color: '#1e1e1e'
                                color_schemes:
                                  Pair:
                                    light: A
                                    dark: B
                                """.trimIndent(),
                            ),
                    ),
                )
            val pair = sections.getValue("preset_color_schemes")["Pair"]?.mapping!!
            pair["light"]?.mapping!!["back_color"]?.string shouldBe "#ffffff"
            pair["dark"]?.mapping!!["back_color"]?.string shouldBe "#1e1e1e"
        }
    })
