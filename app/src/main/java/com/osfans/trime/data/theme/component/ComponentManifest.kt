// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.boolean
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string

/** A parsed component manifest (`component.yaml` or a `manifest.yaml` with `components`). */
data class ComponentManifest(
    val name: String,
    val author: String?,
    val version: String?,
    val useStandardPresetKeys: Boolean,
    val standardKeyboards: List<String>,
    val standardColorSchemes: List<String>,
    val components: List<ComponentEntry>,
) {
    sealed interface ComponentEntry {
        /** A string entry: `standard` or a relative component directory. */
        data class Reference(val name: String) : ComponentEntry

        /** `schema: {file: ...}` */
        data class Schema(val file: String) : ComponentEntry

        data class Keyboard(val spec: ComponentSpec) : ComponentEntry
        data class Behavior(val spec: ComponentSpec) : ComponentEntry
        data class Color(val spec: ComponentSpec) : ComponentEntry
        data class Style(val spec: ComponentSpec) : ComponentEntry
        data class Resources(val spec: ComponentSpec) : ComponentEntry
    }

    companion object {
        fun parse(node: Node.Mapping): ComponentManifest {
            val name = node["name"]?.string
                ?: throw IllegalArgumentException("Component manifest missing 'name'")
            val componentsNode = node["components"]?.sequence
                ?: throw IllegalArgumentException("Component manifest missing 'components'")
            val components = componentsNode.nodes.map { parseEntry(it) }
            return ComponentManifest(
                name = name,
                author = node["author"]?.string,
                version = node["version"]?.string,
                useStandardPresetKeys = node["use_standard_preset_keys"]?.boolean ?: false,
                standardKeyboards = node["standard_keyboards"]?.sequence?.nodes?.mapNotNull { it.string } ?: emptyList(),
                standardColorSchemes = node["standard_color_schemes"]?.sequence?.nodes?.mapNotNull { it.string } ?: emptyList(),
                components = components,
            )
        }

        private fun parseEntry(node: Node): ComponentEntry {
            val scalar = node.string
            if (scalar != null) {
                return ComponentEntry.Reference(scalar)
            }
            val mapping = node.mapping
                ?: throw IllegalArgumentException("Component entry must be a string or mapping")
            if (mapping.pairs.size != 1) {
                throw IllegalArgumentException("Component entry must have exactly one key")
            }
            val (keyNode, valueNode) = mapping.pairs.entries.first()
            val key = keyNode.string
                ?: throw IllegalArgumentException("Component entry key must be a string")
            val specNode = valueNode.mapping
                ?: throw IllegalArgumentException("Component '$key' spec must be a mapping")
            val spec = parseSpec(specNode)
            return when (key) {
                "schema" -> ComponentEntry.Schema(spec.singleFile())
                "keyboard" -> ComponentEntry.Keyboard(spec)
                "behavior" -> ComponentEntry.Behavior(spec)
                "color" -> ComponentEntry.Color(spec)
                "style" -> ComponentEntry.Style(spec)
                "resources" -> ComponentEntry.Resources(spec)
                else -> throw IllegalArgumentException("Unknown component kind '$key'")
            }
        }

        private fun parseSpec(node: Node.Mapping): ComponentSpec {
            val files = mutableListOf<String>()
            node["file"]?.string?.let { files += it }
            node["files"]?.sequence?.nodes?.mapNotNullTo(files) { it.string }
            return ComponentSpec(
                files = files,
                add = node["add"]?.mapping,
                override = node["override"]?.mapping,
                remove = node["remove"]?.sequence?.nodes?.mapNotNull { it.string } ?: emptyList(),
            )
        }
    }
}

/** Operations for a component kind. */
data class ComponentSpec(
    val files: List<String>,
    val add: Node.Mapping?,
    val override: Node.Mapping?,
    val remove: List<String>,
) {
    fun singleFile(): String =
        files.singleOrNull()
            ?: throw IllegalArgumentException("Expected exactly one file, got $files")
}
