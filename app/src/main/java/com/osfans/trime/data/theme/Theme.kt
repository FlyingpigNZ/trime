/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.os.Parcelable
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.data.theme.model.Preedit
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.data.theme.model.Window
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import kotlinx.parcelize.Parcelize

/** 主题和样式配置  */
@Parcelize
data class Theme(
    val name: String,
    val generalStyle: GeneralStyle,
    val preedit: Preedit,
    val window: Window,
    val liquidKeyboard: LiquidKeyboard,
    val presetKeys: Map<String, PresetKey>,
    val presetKeyboards: Map<String, TextKeyboard>,
    val colorSchemes: List<ColorScheme>,
    val fallbackColors: Map<String, String>,
    val toolBar: ToolBar,
) : Parcelable {
    companion object {
        fun decode(node: Node.Mapping): Theme = Theme(
            name = node["name"]?.string!!,
            generalStyle = GeneralStyle.decode(node["style"]!!),
            preedit = Preedit.decode(node["preedit"]?.mapping),
            window = Window.decode(node["window"]?.mapping),
            liquidKeyboard = LiquidKeyboard.decode(node["liquid_keyboard"]?.mapping),
            toolBar = ToolBar.decode(node["tool_bar"]?.mapping),
            presetKeys = node["preset_keys"]?.mapping?.entries?.associate {
                it.key.string!! to PresetKey.decode(it.value.mapping!!)
            } ?: emptyMap(),
            presetKeyboards =
            resolveKeyboardIncludes(node["preset_keyboards"]?.mapping).mapValues {
                TextKeyboard.decode(it.value)
            },
            colorSchemes =
            node["preset_color_schemes"]?.mapping?.map {
                ColorScheme(
                    it.key.string!!,
                    it.value.mapping!!.entries.associate { (k, v) ->
                        k.string!! to v.string!!
                    },
                )
            } ?: emptyList(),
            fallbackColors = node["fallback_colors"]?.mapping?.entries?.associate {
                it.key.string!! to it.value.string!!
            } ?: emptyMap(),
        )

        /**
         * Resolve `__include` / `import_preset` inheritance for
         * `preset_keyboards` at parse time.
         *
         * A keyboard may declare `__include: /preset_keyboards/<name>` (or the
         * legacy `import_preset: <name>`); the included keyboard's fields are
         * merged underneath, with the including keyboard's own fields taking
         * precedence. Cycles are detected and reported.
         */
        private fun resolveKeyboardIncludes(mapping: Node.Mapping?): Map<String, Node.Mapping> {
            if (mapping == null) return emptyMap()
            val raw = mapping.pairs.mapNotNull { (k, v) ->
                val name = k.string ?: return@mapNotNull null
                name to (v as? Node.Mapping)
            }.toMap()

            fun resolve(name: String, stack: List<String>): Node.Mapping? {
                val node = raw[name] ?: return null
                val include =
                    node[INCLUDE_KEY]?.string ?: node[IMPORT_PRESET_KEY]?.string ?: return node
                val target = include.substringAfterLast('/').ifEmpty { return node }

                if (target in stack) {
                    throw IllegalArgumentException(
                        "Circular $INCLUDE_KEY in preset_keyboards: ${(stack + name).joinToString(" -> ")}",
                    )
                }
                val base = resolve(target, stack + name) ?: return node
                // included fields first, then this keyboard's own fields win
                val merged = LinkedHashMap<Node, Node>()
                base.pairs.forEach { (k, v) -> merged[k] = v }
                node.pairs.forEach { (k, v) ->
                    val key = k.string ?: return@forEach
                    // drop the include markers from the merged result
                    if (key != INCLUDE_KEY && key != IMPORT_PRESET_KEY) merged[k] = v
                }
                return Node.Mapping(merged)
            }

            return raw.keys.associateWith { resolve(it, emptyList()) ?: Node.Mapping() }
        }

        private const val INCLUDE_KEY = "__include"
        private const val IMPORT_PRESET_KEY = "import_preset"
    }
}
