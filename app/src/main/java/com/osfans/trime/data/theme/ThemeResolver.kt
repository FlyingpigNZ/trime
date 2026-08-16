// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.boolean
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string

/**
 * Merges the three definition tiers into the single runtime [Theme] shape that
 * views consume.
 *
 * Tier-1 components are explicit-by-name only (D1): a theme opts into standard
 * preset keys with `use_standard_preset_keys: true`, and lists standard
 * keyboards/colors in `standard_keyboards` / `standard_color_schemes`. Entries
 * in the theme's own sections still override/extend the selected standard
 * entries, so legacy monolithic themes keep working without declarations.
 */
object ThemeResolver {
    fun resolve(
        theme: Node.Mapping,
        standard: StandardCatalog,
    ): Theme = Theme.decode(merge(theme, standard))

    fun merge(
        theme: Node.Mapping,
        standard: StandardCatalog,
    ): Node.Mapping {
        val merged = LinkedHashMap<Node, Node>(theme.pairs)
        val useStandardPresetKeys = theme["use_standard_preset_keys"]?.boolean ?: false
        val standardKeyboardNames =
            theme["standard_keyboards"]?.sequence?.mapNotNull { it.string }?.toSet() ?: emptySet()
        val standardColorSchemeNames =
            theme["standard_color_schemes"]?.sequence?.mapNotNull { it.string }?.toSet() ?: emptySet()

        validateDeclaredNames(
            "standard_keyboards",
            standardKeyboardNames,
            standard.presetKeyboards,
        )
        validateDeclaredNames(
            "standard_color_schemes",
            standardColorSchemeNames,
            standard.presetColorSchemes,
        )

        merged[Node.Scalar("preset_keys")] =
            if (useStandardPresetKeys) {
                mergeSection(theme["preset_keys"]?.mapping, standard.presetKeys)
            } else {
                theme["preset_keys"]?.mapping ?: Node.Mapping()
            }
        val expandedKeyboardNames =
            expandKeyboardSelection(standardKeyboardNames, standard.presetKeyboards)
        merged[Node.Scalar("preset_keyboards")] =
            mergeSelected(
                theme["preset_keyboards"]?.mapping,
                standard.presetKeyboards,
                expandedKeyboardNames,
            )
        merged[Node.Scalar("preset_color_schemes")] =
            mergeSelected(
                theme["preset_color_schemes"]?.mapping,
                standard.presetColorSchemes,
                standardColorSchemeNames,
            )
        return Node.Mapping(merged)
    }

    private fun validateDeclaredNames(
        key: String,
        names: Set<String>,
        standardSection: Node.Mapping,
    ) {
        val unknown = names.filterNot { standardSection[it] != null }
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown $key entries: ${unknown.joinToString()}. " +
                    "These names are not present in the standard catalog.",
            )
        }
    }

    private fun expandKeyboardSelection(
        selectedNames: Set<String>,
        standardSection: Node.Mapping,
    ): Set<String> {
        val expanded = selectedNames.toMutableSet()
        fun addDependencies(name: String) {
            val node = standardSection[name] as? Node.Mapping ?: return
            val include = node["__include"]?.string ?: return
            val target = include.substringAfterLast('/')
            if (target.isEmpty() || target in expanded) return
            expanded += target
            addDependencies(target)
        }
        selectedNames.toList().forEach(::addDependencies)
        return expanded
    }

    private fun mergeSelected(
        themeSection: Node.Mapping?,
        standardSection: Node.Mapping,
        selectedNames: Set<String>,
    ): Node.Mapping {
        val result = LinkedHashMap<Node, Node>()
        standardSection.pairs.forEach { (key, value) ->
            val name = key.string ?: return@forEach
            if (name in selectedNames) {
                result[key] = value
            }
        }
        themeSection?.pairs?.forEach { (key, value) -> result[key] = value }
        return Node.Mapping(result)
    }

    private fun mergeSection(
        themeSection: Node.Mapping?,
        standardSection: Node.Mapping,
    ): Node.Mapping {
        val result = LinkedHashMap<Node, Node>()
        standardSection.pairs.forEach { (key, value) -> result[key] = value }
        themeSection?.pairs?.forEach { (key, value) -> result[key] = value }
        return Node.Mapping(result)
    }
}
