/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

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

/** 主题和样式配置  */
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
) {
    /**
     * Merge a schema-layout theme (already resolved against the standard
     * catalog) on top of this decoration theme. The layout contributes preset
     * keys/keyboards/color schemes; all chrome stays with the base theme.
     */
    fun mergeSchemaLayout(layout: Theme): Theme = copy(
        presetKeys = presetKeys + layout.presetKeys,
        presetKeyboards = presetKeyboards + layout.presetKeyboards,
        colorSchemes = mergeColorSchemes(colorSchemes, layout.colorSchemes),
    )

    private fun mergeColorSchemes(
        base: List<ColorScheme>,
        overlay: List<ColorScheme>,
    ): List<ColorScheme> {
        val byId = base.associateBy { it.id }.toMutableMap()
        overlay.forEach { byId[it.id] = it }
        val order = base.mapNotNull { byId.remove(it.id) } + byId.values
        return order
    }

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
            colorSchemes = decodeColorSchemes(node),
            fallbackColors = node["fallback_colors"]?.mapping?.entries?.associate {
                it.key.string!! to it.value.string!!
            } ?: emptyMap(),
        )

        /**
         * Resolve `__include` inheritance for `preset_keyboards` at parse time.
         *
         * A keyboard may declare `__include: /preset_keyboards/<name>`; the
         * included keyboard's fields are merged underneath, with the including
         * keyboard's own fields taking precedence. Cycles and unknown targets
         * are reported instead of silently dropping the inheritance.
         */
        private fun decodeColorSchemes(node: Node.Mapping): List<ColorScheme> {
            val palettes =
                node["colors"]?.mapping?.pairs
                    ?.mapNotNull { (key, value) ->
                        val name = key.string ?: return@mapNotNull null
                        val palette = value as? Node.Mapping ?: return@mapNotNull null
                        name to palette
                    }
                    ?.toMap()
                    ?: emptyMap()
            val schemes = mutableListOf<ColorScheme>()

            node["color_schemes"]?.mapping?.pairs?.forEach { (schemeKey, schemeValue) ->
                val id = schemeKey.string ?: return@forEach
                val pair = schemeValue.mapping ?: return@forEach
                val lightName = pair["light"]?.string ?: return@forEach
                val darkName = pair["dark"]?.string ?: lightName
                val lightPalette = palettes[lightName] ?: Node.Mapping()
                val darkPalette = palettes[darkName] ?: lightPalette
                val topName = pair["name"]?.string
                val light = decodePaletteMapping(lightPalette).toMutableMap()
                val dark = decodePaletteMapping(darkPalette).toMutableMap()
                topName?.let { name ->
                    if ("name" !in light) light["name"] = name
                    if ("name" !in dark) dark["name"] = name
                }
                schemes += ColorScheme(id, light, dark)
            }

            node["preset_color_schemes"]?.mapping?.pairs?.forEach { (schemeKey, schemeValue) ->
                schemeKey.string?.let { id ->
                    schemeValue.mapping?.let { schemeNode ->
                        schemes += decodeColorScheme(id, schemeNode, palettes)
                    }
                }
            }
            return schemes
        }

        private fun decodeColorScheme(
            id: String,
            node: Node.Mapping,
            palettes: Map<String, Node.Mapping>,
        ): ColorScheme {
            val lightNode =
                node["light"]?.mapping
                    ?: node["light"]?.string?.let { palettes[it] }
            val darkNode =
                node["dark"]?.mapping
                    ?: node["dark"]?.string?.let { palettes[it] }
            val topName = node["name"]?.string
            val metaKeys = setOf("light", "dark", "name")

            fun decodePalette(mapping: Node.Mapping?): MutableMap<String, String> =
                mapping?.entries
                    ?.associate { (k, v) -> k.string!! to v.string!! }
                    ?.toMutableMap()
                    ?: node.pairs
                        .mapNotNull { (k, v) ->
                            val key = k.string ?: return@mapNotNull null
                            if (key in metaKeys) null else key to v.string!!
                        }
                        .toMap()
                        .toMutableMap()

            val light = decodePalette(lightNode)
            val dark = decodePalette(darkNode).ifEmpty { light }.toMutableMap()
            topName?.let { name ->
                if ("name" !in light) light["name"] = name
                if ("name" !in dark) dark["name"] = name
            }
            return ColorScheme(id, light, dark)
        }

        private fun decodePaletteMapping(mapping: Node.Mapping): Map<String, String> =
            mapping.entries.associate { (k, v) -> k.string!! to v.string!! }

        private fun resolveKeyboardIncludes(mapping: Node.Mapping?): Map<String, Node.Mapping> {
            if (mapping == null) return emptyMap()
            val raw = mapping.pairs.mapNotNull { (k, v) ->
                val name = k.string ?: return@mapNotNull null
                name to (v as? Node.Mapping)
            }.toMap()

            fun resolve(name: String, stack: List<String>): Node.Mapping? {
                val node = raw[name] ?: return null
                val include = node[INCLUDE_KEY]?.string ?: return node
                val target = include.substringAfterLast('/').ifEmpty { return node }

                if (target in stack) {
                    throw IllegalArgumentException(
                        "Circular $INCLUDE_KEY in preset_keyboards: ${(stack + name).joinToString(" -> ")}",
                    )
                }
                val base =
                    resolve(target, stack + name)
                        ?: throw IllegalArgumentException(
                            "Unknown $INCLUDE_KEY target '$include' in preset_keyboards entry '$name'",
                        )
                // included fields first, then this keyboard's own fields win
                val merged = LinkedHashMap<Node, Node>()
                base.pairs.forEach { (k, v) -> merged[k] = v }
                node.pairs.forEach { (k, v) ->
                    val key = k.string ?: return@forEach
                    // drop the include marker from the merged result
                    if (key != INCLUDE_KEY) merged[k] = v
                }
                return Node.Mapping(merged)
            }

            return raw.keys.associateWith { resolve(it, emptyList()) ?: Node.Mapping() }
        }

        private const val INCLUDE_KEY = "__include"
    }
}
