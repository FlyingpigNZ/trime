// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain

class BehaviorVerifierTest :
    StringSpec({
        fun mapping(yaml: String): Node.Mapping =
            Yaml.Default.parseToYamlNode(yaml.trimIndent()).mapping!!

        fun sections(yaml: String): Map<String, Node.Mapping> {
            val root = mapping(yaml)
            return mapOf(
                "preset_keys" to (root["preset_keys"]?.mapping ?: Node.Mapping()),
                "preset_keyboards" to (root["preset_keyboards"]?.mapping ?: Node.Mapping()),
                "keyboard_switch_policy" to (root["keyboard_switch_policy"]?.mapping ?: Node.Mapping()),
                "style" to (root["style"]?.mapping ?: Node.Mapping()),
            )
        }

        "valid definitions pass" {
            val errors =
                BehaviorVerifier.verify(
                    sections(
                        """
                        preset_keys:
                          space: {label: 空格, send: space}
                          Keyboard_number: {label: '123', send: Eisu_toggle, select: number}
                          Punct_switch: {toggle: ascii_punct, states: ['。，', '．，']}
                        preset_keyboards:
                          default:
                            ascii_keyboard: letter
                            keys:
                              - {click: space}
                              - {click: a}
                              - {click: Keyboard_number}
                              - {click: Punct_switch}
                              - {click: 1}
                          letter: {keys: []}
                          number: {keys: []}
                        keyboard_switch_policy:
                          default_keyboard: default
                          ascii_keyboard: letter
                          aux:
                            number: number
                        style:
                          keyboards: [default]
                        """.trimIndent(),
                    ),
                )
            errors.shouldBeEmpty()
        }

        "missing ascii keyboard fails" {
            val errors =
                BehaviorVerifier.verify(
                    sections(
                        """
                        preset_keyboards:
                          default:
                            ascii_keyboard: missing
                            keys: []
                        """.trimIndent(),
                    ),
                )
            errors.shouldContain("keyboard 'default'.ascii_keyboard references unknown keyboard 'missing'")
        }

        "select unknown keyboard fails" {
            val errors =
                BehaviorVerifier.verify(
                    sections(
                        """
                        preset_keys:
                          Bad: {send: Eisu_toggle, select: missing}
                        preset_keyboards:
                          default:
                            keys:
                              - {click: Bad}
                        """.trimIndent(),
                    ),
                )
            errors.shouldContain("keyboard 'default' key[0] click 'Bad': 'select' references unknown keyboard 'missing'")
        }

        "toggle without states fails" {
            val errors =
                BehaviorVerifier.verify(
                    sections(
                        """
                        preset_keys:
                          Bad: {toggle: ascii_mode}
                        preset_keyboards:
                          default:
                            keys:
                              - {click: Bad}
                        """.trimIndent(),
                    ),
                )
            errors.shouldContain("keyboard 'default' key[0] click 'Bad': toggle 'ascii_mode' must have a non-empty 'states' list")
        }
    })
