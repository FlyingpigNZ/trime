// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.get
import com.osfans.trime.util.yaml.mapping
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Compatibility check against the shipped Default package keyboard data: the
 * preview's pure layout pass must reproduce the same rows the IME wraps for
 * the real `preset_keyboards` entry it renders.
 */
class DefaultKeyboardLayoutCompatibilityTest :
    StringSpec({
        "shipped 'default' keyboard wraps into expected rows" {
            val keyboard = loadShippedDefaultKeyboard()
            val specs =
                keyboard.keys.map {
                    PreviewKeyInput(it.width, it.height, it.hasClickAction)
                }
            val layout =
                PreviewKeyboardLayout.build(
                    keys = specs,
                    defaultWidthWeight = keyboard.width,
                    defaultRowHeightDp = keyboard.height.takeIf { it > 0f } ?: 44f,
                    maxColumns = keyboard.columns,
                )

            layout.isEmpty shouldBe false
            // Rows: letters 10 keys, letters 9 keys (two 5-weight side pads),
            // letter/bottom rows below.
            layout.rows.size shouldBe 4
            val letterRow = layout.rows[0]
            letterRow.keys.size shouldBe 10
            letterRow.keys.first().leftWeight shouldBe 0f
            letterRow.keys.last().rightWeight shouldBe 100f

            val secondRow = layout.rows[1]
            secondRow.keys.size shouldBe 9
            secondRow.keys.first().leftWeight shouldBe 5f
            secondRow.keys.last().rightWeight shouldBe 95f
            secondRow.totalWidthWeight shouldBe 100f

            // Every placed key must stay inside the 100-weight row width.
            layout.rows.forEach { row ->
                row.keys.forEach { placed ->
                    (placed.leftWeight >= 0f) shouldBe true
                    (placed.rightWeight <= PreviewKeyboardLayout.MAX_TOTAL_WEIGHT + 0.001f) shouldBe true
                    (placed.widthWeight > 0f) shouldBe true
                    (placed.rightWeight - placed.leftWeight) shouldBe placed.widthWeight.plusOrMinus(0.001f)
                }
            }
        }
    })

private fun loadShippedDefaultKeyboard(): TextKeyboard {
    val file = File("src/main/assets/shared/Default/keyboard.yaml")
    check(file.isFile) { "shipped keyboard file missing: ${file.absolutePath}" }
    val root = Yaml.Default.parseToYamlNode(file.readText())
    require(root is Node.Mapping) { "expected a YAML mapping" }
    val presets = root["preset_keyboards"]?.mapping
        ?: error("preset_keyboards missing")
    val keyboardNode = presets["default"]?.mapping
        ?: error("preset_keyboards.default missing")
    return TextKeyboard.decode(keyboardNode)
}
