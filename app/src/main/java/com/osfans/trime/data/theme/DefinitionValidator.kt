// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string

/**
 * Pure validation core for resolved component sections and the app-owned
 * customization overlay.
 *
 * Full package-manifest validation (manifest plus its referenced component
 * files) lives in `ComponentValidator` and runs when a package or theme is
 * loaded; the desktop CLI mirrors these checks in `script/`. Checks return a
 * list of error messages instead of throwing, so callers can surface all
 * problems at once.
 */
object DefinitionValidator {
    /** Palette keys that accept a drawable value (image file name or color). */
    val DRAWABLE_KEYS =
        setOf(
            "root_background",
            "candidate_background",
            "keyboard_background",
            "liquid_keyboard_background",
        )

    private val CUSTOMIZATION_MODES = setOf("light", "dark")

    /** Validate color literal formats in resolved component sections. */
    fun validateColorLiterals(sections: Map<String, Node.Mapping>): List<String> {
        val errors = mutableListOf<String>()
        val knownColorKeys = mutableSetOf<String>()
        ThemeColor.entries.forEach { knownColorKeys += it.key }
        // The runtime resolves these builtin fallback keys even when a
        // self-contained package does not redefine them in every color scheme.
        knownColorKeys += BuiltinFallbackColors.keys

        sections["preset_color_schemes"]?.pairs?.forEach { (_, schemeNode) ->
            val scheme = schemeNode.mapping ?: return@forEach
            val palettes = mutableListOf<Node.Mapping>()
            scheme["light"]?.mapping?.let { palettes += it }
            scheme["dark"]?.mapping?.let { palettes += it }
            if (palettes.isEmpty()) palettes += scheme
            palettes.forEach { palette ->
                palette.pairs.forEach { (keyNode, valueNode) ->
                    val key = keyNode.string ?: return@forEach
                    if (key == "name" || key == "author") return@forEach
                    knownColorKeys += key
                    if (key in DRAWABLE_KEYS) return@forEach
                    val value = valueNode.string ?: return@forEach
                    if (!isHexColor(value)) {
                        errors += "preset_color_schemes: invalid color '$value' for '$key'"
                    }
                }
            }
        }

        val fallbackColors = linkedMapOf<String, String>()
        sections["fallback_colors"]?.pairs?.forEach { (keyNode, valueNode) ->
            val key = keyNode.string ?: return@forEach
            val value = valueNode.string ?: return@forEach
            fallbackColors[key] = value
        }
        // Two-pass fallback validation: all fallback keys are known before any
        // reference is checked, so definitions are order-independent.
        fallbackColors.keys.forEach { knownColorKeys += it }
        fallbackColors.forEach { (key, value) ->
            if (!isHexColor(value) && value !in knownColorKeys) {
                errors += "fallback_colors: invalid color reference '$value' for '$key'"
            }
        }
        errors += validateFallbackCycles(fallbackColors)

        sections["tool_bar"]?.let { validateColorFields(it, "tool_bar", knownColorKeys, errors) }
        return errors
    }

    /**
     * Validate the app-owned `customization.yaml` overlay against the resolved
     * component [sections]. Mirrors the palette value rules used for
     * color.yaml (drawable keys may carry image file names, other keys must be
     * hex literals) and rejects unknown schemes / modes / keys loudly instead
     * of silently dropping them.
     */
    fun validateCustomization(
        sections: Map<String, Node.Mapping>,
        customization: Node.Mapping,
    ): List<String> {
        val errors = mutableListOf<String>()
        val rootKey = ThemeCustomization.COLOR_SCHEMES_KEY
        customization.pairs.keys.mapNotNull { it.string }
            .filterNot { it == rootKey }
            .forEach { errors += "customization.yaml: unknown key '$it'" }
        val overrides = customization[rootKey]?.mapping ?: return errors
        val preset = sections["preset_color_schemes"]?.mapping
            ?: run {
                errors += "customization.yaml: theme has no 'preset_color_schemes' to customize"
                return errors
            }
        overrides.pairs.forEach { (idNode, overrideNode) ->
            val id = idNode.string
            if (id == null) {
                errors += "customization.yaml: scheme id must be a string"
                return@forEach
            }
            val override = overrideNode.mapping
            if (override == null) {
                errors += "customization.yaml: color_schemes.$id must be a mapping"
                return@forEach
            }
            val scheme = preset[Node.Scalar(id)]?.mapping
            if (scheme == null) {
                errors += "customization.yaml: unknown color scheme '$id'"
                return@forEach
            }
            val allowedKeys = schemeOverrideKeys(scheme)
            override.pairs.forEach { (modeNode, paletteNode) ->
                val mode = modeNode.string
                if (mode == null || mode !in CUSTOMIZATION_MODES) {
                    errors += "customization.yaml: color_schemes.$id: mode must be 'light' or 'dark'"
                    return@forEach
                }
                val palette = paletteNode.mapping
                if (palette == null) {
                    errors += "customization.yaml: color_schemes.$id.$mode must be a mapping"
                    return@forEach
                }
                palette.pairs.forEach { (keyNode, valueNode) ->
                    val key = keyNode.string ?: return@forEach
                    if (key !in allowedKeys) {
                        errors += "customization.yaml: color_schemes.$id.$mode: unknown key '$key'"
                    }
                    if (key in DRAWABLE_KEYS) return@forEach
                    val value = valueNode.string
                    if (value == null || !isHexColor(value)) {
                        errors += "customization.yaml: color_schemes.$id.$mode.$key: invalid color '$value'"
                    }
                }
            }
        }
        return errors
    }

    /**
     * Keys a customization entry may override for [scheme]: the scheme's own
     * palette keys (light and/or dark; for legacy flat schemes the flat map
     * itself) plus every known theme color key and builtin fallback key, so a
     * scheme that currently falls back can still gain an explicit value.
     */
    private fun schemeOverrideKeys(scheme: Node.Mapping): Set<String> {
        val keys = mutableSetOf<String>()
        fun collect(palette: Node.Mapping) {
            palette.pairs.keys.mapNotNull { it.string }
                .filterNot { it == "name" || it == "author" }
                .forEach { keys += it }
        }
        scheme["light"]?.mapping?.let { collect(it) }
        scheme["dark"]?.mapping?.let { collect(it) }
        if (scheme["light"]?.mapping == null && scheme["dark"]?.mapping == null) collect(scheme)
        keys += ThemeColor.entries.map { it.key }
        keys += BuiltinFallbackColors.keys
        return keys
    }

    /**
     * Validate every `<schemaId>.extended.yaml` in [workspace]: structural
     * checks via [SchemaExtension.validate] plus color-literal checks on the
     * `tool_bar` section.
     *
     * [sections] carries the resolved component sections so the toolbar color
     * check knows the package's own color keys (extended files reference those
     * keys); pass an empty map to only check against the builtin keys.
     */
    fun validateExtendedFiles(
        workspace: java.io.File,
        sections: Map<String, Node.Mapping> = emptyMap(),
    ): List<String> {
        val errors = mutableListOf<String>()
        val colorKeys = knownColorKeys(sections)
        workspace.listFiles { file ->
            file.isFile && file.name.endsWith(SchemaExtension.FILE_SUFFIX)
        }?.sortedBy { it.name }?.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            errors += SchemaExtension.validate(text, file.name)
            val node = parseMapping(text)
            // The schema_id declared inside the file must match its file name:
            // `<schemaId>.extended.yaml` is loaded by translating the file name
            // to a schema id, so a mismatch would silently bind the wrong id.
            node?.get("schema_id")?.string?.let { declared ->
                val fromName = file.name.removeSuffix(SchemaExtension.FILE_SUFFIX)
                if (declared != fromName) {
                    errors += "${file.name}: schema_id '$declared' does not match file name '$fromName'"
                }
            }
            // Color-literal validation for the toolbar section.
            node?.get("tool_bar")?.mapping?.let { toolBar ->
                validateColorFields(toolBar, "${file.name}.tool_bar", colorKeys, errors)
            }
        }
        return errors
    }

    /**
     * All color keys the toolbar may reference: builtin theme keys, builtin
     * fallback keys, plus every color key defined by the package's color
     * schemes.
     */
    fun knownColorKeys(sections: Map<String, Node.Mapping> = emptyMap()): Set<String> {
        val known = mutableSetOf<String>()
        ThemeColor.entries.forEach { known += it.key }
        known += BuiltinFallbackColors.keys
        sections["preset_color_schemes"]?.pairs?.forEach { (_, schemeNode) ->
            val scheme = schemeNode.mapping ?: return@forEach
            val palettes = mutableListOf<Node.Mapping>()
            scheme["light"]?.mapping?.let { palettes += it }
            scheme["dark"]?.mapping?.let { palettes += it }
            if (palettes.isEmpty()) palettes += scheme
            palettes.forEach { palette ->
                palette.pairs.forEach { (keyNode, _) ->
                    keyNode.string?.let { known += it }
                }
            }
        }
        sections["fallback_colors"]?.pairs?.forEach { (keyNode, _) ->
            keyNode.string?.let { known += it }
        }
        return known
    }

    private fun validateFallbackCycles(fallbackColors: Map<String, String>): List<String> {
        val errors = mutableListOf<String>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(
            key: String,
            stack: List<String>,
        ) {
            if (key in visited) return
            if (!visiting.add(key)) {
                val cycleStart = stack.indexOf(key)
                val cycle =
                    if (cycleStart >= 0) stack.subList(cycleStart, stack.size) + key else stack + key
                errors += "fallback_colors: cycle detected: ${cycle.joinToString(" -> ")}"
                return
            }
            val value = fallbackColors[key]
            if (value != null && !isHexColor(value) && value in fallbackColors) {
                visit(value, stack + key)
            }
            visiting.remove(key)
            visited.add(key)
        }

        fallbackColors.keys.forEach { visit(it, emptyList()) }
        return errors.distinct()
    }

    private fun validateColorFields(
        node: Node,
        path: String,
        knownColorKeys: Set<String>,
        errors: MutableList<String>,
    ) {
        when (node) {
            is Node.Mapping ->
                node.pairs.forEach { (keyNode, valueNode) ->
                    val key = keyNode.string ?: return@forEach
                    val childPath = "$path.$key"
                    if (key == "normal" || key == "highlight") {
                        val value = valueNode.string
                        if (value != null && !isHexColor(value) && value !in knownColorKeys) {
                            errors += "$childPath: invalid color '$value'"
                        }
                    }
                    validateColorFields(valueNode, childPath, knownColorKeys, errors)
                }
            is Node.Sequence ->
                node.nodes.forEachIndexed { index, item ->
                    validateColorFields(item, "$path[$index]", knownColorKeys, errors)
                }
            else -> Unit
        }
    }

    private fun isHexColor(value: String): Boolean {
        val body =
            when {
                value.startsWith("0x", ignoreCase = true) -> value.substring(2)
                value.startsWith("#") -> value.substring(1)
                else -> return false
            }
        return body.length in 1..8 && body.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    }

    private fun parseMapping(yaml: String): Node.Mapping? = try {
        Yaml.Default.parseToYamlNode(yaml).mapping
    } catch (_: Exception) {
        null
    }
}
