// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string

/**
 * Pure component resolver.
 *
 * Resolves a [ComponentManifest] into the merged definition sections that the
 * runtime decodes into a [com.osfans.trime.data.theme.Theme].
 */
class ComponentResolver(
    private val source: ComponentSource,
) {
    private val sections: MutableMap<String, Node.Mapping> =
        SECTION_NAMES.associateWith { Node.Mapping() }.toMutableMap()

    fun resolve(manifest: ComponentManifest): Map<String, Node.Mapping> {
        sections.keys.forEach { sections[it] = Node.Mapping() }
        manifest.components.forEach { resolveEntry(it) }
        return sections.toMap()
    }

    // ── entries ───────────────────────────────────────────────────────────
    private fun resolveEntry(entry: ComponentManifest.ComponentEntry) {
        when (entry) {
            is ComponentManifest.ComponentEntry.Reference -> resolveReference(entry.name)
            is ComponentManifest.ComponentEntry.Schema -> Unit // schema files are handled by the package installer
            is ComponentManifest.ComponentEntry.Keyboard -> {
                entry.spec.files.forEach { file -> applyComponentData(source.load(file)) }
                applyOperations("preset_keyboards", entry.spec)
            }
            is ComponentManifest.ComponentEntry.Behavior -> {
                entry.spec.files.forEach { file -> applyComponentData(source.load(file)) }
                applyOperations("preset_keys", entry.spec)
            }
            is ComponentManifest.ComponentEntry.Color -> {
                entry.spec.files.forEach { file -> applyComponentData(source.load(file)) }
                applyOperations("preset_color_schemes", entry.spec)
            }
            is ComponentManifest.ComponentEntry.Style -> resolveStyle(entry.spec)
            is ComponentManifest.ComponentEntry.Resources -> Unit // resource list handling TBD
        }
    }

    private fun resolveReference(name: String) {
        loadComponentDirectory(name)
    }

    // ── loading ────────────────────────────────────────────────────────────
    private fun loadComponentDirectory(directory: String) {
        val base = directory.trimEnd('/')
        listOf(
            "keyboard.yaml",
            "behavior.yaml",
            "color.yaml",
            "style.yaml",
        ).forEach { file ->
            val path = "$base/$file"
            val loaded = runCatching { source.load(path) }.getOrNull() ?: return@forEach
            applyComponentData(loaded)
        }
    }

    private fun applyComponentData(
        data: Node.Mapping,
        onlySection: String? = null,
    ) {
        val resolvedData = resolveColorSchemes(data)
        val sectionsToApply =
            if (onlySection != null) listOf(onlySection)
            else SECTION_NAMES
        sectionsToApply.forEach { section ->
            resolvedData[section]?.mapping?.let { value ->
                if (section in NAMED_SECTIONS) {
                    val result = LinkedHashMap(sections.getValue(section).pairs)
                    value.pairs.forEach { (key, entry) -> result[key] = entry }
                    sections[section] = Node.Mapping(result)
                } else {
                    sections[section] = mergeMappings(sections.getValue(section), value)
                }
            }
        }
    }

    // ── kind operations ────────────────────────────────────────────────────
    private fun applyOperations(section: String, spec: ComponentSpec) {
        val target = sections.getValue(section)
        val result = LinkedHashMap<Node, Node>(target.pairs)

        spec.add?.pairs?.forEach { (key, value) ->
            if (result.containsKey(key)) {
                throw IllegalArgumentException(
                    "$section.add: '${key.string}' already exists; use override",
                )
            }
            result[key] = value
        }

        spec.override?.pairs?.forEach { (key, value) ->
            if (!result.containsKey(key)) {
                throw IllegalArgumentException(
                    "$section.override: '${key.string}' does not exist; use add",
                )
            }
            result[key] = value
        }

        spec.remove.forEach { name ->
            val key = Node.Scalar(name)
            if (!result.containsKey(key)) {
                throw IllegalArgumentException("$section.remove: '$name' does not exist")
            }
            result.remove(key)
        }

        sections[section] = Node.Mapping(result)
    }

    // ── style ──────────────────────────────────────────────────────────────
    private fun resolveStyle(spec: ComponentSpec) {
        spec.files.forEach { file ->
            applyComponentData(source.load(file))
        }
        spec.override?.let { overrides ->
            sections["style"] = mergeMappings(sections.getValue("style"), overrides)
        }
    }

    companion object {
        private val NAMED_SECTIONS =
            setOf("preset_keys", "preset_keyboards", "preset_color_schemes")

        private val SECTION_NAMES =
            listOf(
                "preset_keys",
                "preset_keyboards",
                "preset_color_schemes",
                "switches",
                "keyboard_switch_policy",
                "style",
                "preedit",
                "window",
                "tool_bar",
                "liquid_keyboard",
                "fallback_colors",
                "resources",
            )

        private fun resolveColorSchemes(data: Node.Mapping): Node.Mapping {
            val colors = data["colors"]?.mapping ?: return data
            val schemes = data["color_schemes"]?.mapping ?: return data
            val palettes = LinkedHashMap<String, Node.Mapping>()
            colors.pairs.forEach { (key, value) ->
                val name = key.string ?: return@forEach
                val palette =
                    value.mapping
                        ?: throw IllegalArgumentException(
                            "'colors' must be a mapping of palette name to palette mapping",
                        )
                palettes[name] = palette
            }
            val resolved = LinkedHashMap<Node, Node>()
            schemes.pairs.forEach { (schemeKey, schemeValue) ->
                val id = schemeKey.string ?: return@forEach
                val pair =
                    schemeValue.mapping
                        ?: throw IllegalArgumentException("color_schemes.$id: must be a mapping")
                val lightName =
                    pair["light"]?.string
                        ?: throw IllegalArgumentException("color_schemes.$id: missing 'light' palette")
                val darkName = pair["dark"]?.string ?: lightName
                val light =
                    palettes[lightName]
                        ?: throw IllegalArgumentException("color_schemes.$id: unknown light palette '$lightName'")
                val dark =
                    if (darkName == lightName) light
                    else palettes[darkName]
                        ?: throw IllegalArgumentException("color_schemes.$id: unknown dark palette '$darkName'")
                val scheme = LinkedHashMap<Node, Node>()
                pair["name"]?.let { scheme[Node.Scalar("name")] = it }
                pair["author"]?.let { scheme[Node.Scalar("author")] = it }
                scheme[Node.Scalar("light")] = light
                scheme[Node.Scalar("dark")] = dark
                resolved[Node.Scalar(id)] = Node.Mapping(scheme)
            }
            val newPairs = LinkedHashMap(data.pairs)
            newPairs[Node.Scalar("preset_color_schemes")] = Node.Mapping(resolved)
            return Node.Mapping(newPairs)
        }

        private fun mergeMappings(base: Node.Mapping, override: Node.Mapping): Node.Mapping {
            val result = LinkedHashMap<Node, Node>(base.pairs)
            override.pairs.forEach { (key, value) ->
                val existing = result[key]
                if (existing is Node.Mapping && value is Node.Mapping) {
                    result[key] = mergeMappings(existing, value)
                } else {
                    result[key] = value
                }
            }
            return Node.Mapping(result)
        }
    }
}
