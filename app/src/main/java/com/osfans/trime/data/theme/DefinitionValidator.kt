// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.schema.SchemaLayoutManifest
import com.osfans.trime.data.theme.component.ComponentManifest
import com.osfans.trime.data.theme.component.ComponentResolver
import com.osfans.trime.data.theme.component.ComponentSource
import com.osfans.trime.data.theme.component.ComponentValidator
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string

/**
 * Pure validation core for definition files.
 *
 * The same checks are used by the in-app validator, the CLI tool, and CI.
 * It intentionally returns a list of error messages instead of throwing, so
 * callers can show all problems at once.
 */
object DefinitionValidator {
    fun validateTheme(
        yaml: String,
        standard: StandardCatalog,
    ): List<String> {
        val node = parseMapping(yaml) ?: return listOf("Theme must be a YAML mapping")
        val errors = mutableListOf<String>()
        if (node["name"]?.string.isNullOrBlank()) {
            errors += "Missing required field 'name'"
        }
        if (node["style"]?.mapping == null) {
            errors += "Missing required field 'style'"
        }
        try {
            ThemeResolver.resolve(node, standard)
        } catch (e: Exception) {
            errors += e.message ?: "Invalid theme definition"
        }
        return errors
    }

    fun validateLayoutFragment(
        yaml: String,
        standard: StandardCatalog,
    ): List<String> {
        val node = parseMapping(yaml) ?: return listOf("Layout fragment must be a YAML mapping")
        val complete = Node.Mapping(LinkedHashMap(node.pairs).apply {
            putIfAbsent(Node.Scalar("name"), Node.Scalar("fragment"))
            putIfAbsent(Node.Scalar("style"), Node.Mapping())
        })
        return try {
            ThemeResolver.resolve(complete, standard)
            emptyList()
        } catch (e: IllegalArgumentException) {
            listOf(e.message ?: "Invalid layout fragment")
        }
    }

    fun validateManifest(yaml: String): List<String> =
        try {
            SchemaLayoutManifest.parse(yaml)
            emptyList()
        } catch (e: IllegalArgumentException) {
            listOf(e.message ?: "Invalid schema layout manifest")
        }

    fun validateComponentManifest(
        yaml: String,
        source: ComponentSource? = null,
    ): List<String> {
        if (source == null) {
            return try {
                ComponentManifest.parse(parseMapping(yaml) ?: return listOf("Component manifest must be a YAML mapping"))
                emptyList()
            } catch (e: Exception) {
                listOf(e.message ?: "Invalid component manifest")
            }
        }
        return ComponentValidator.validate(yaml, source)
    }

    /** Validate color literal formats in resolved component sections. */
    fun validateColorLiterals(sections: Map<String, Node.Mapping>): List<String> {
        val errors = mutableListOf<String>()
        val knownColorKeys = mutableSetOf<String>()
        ThemeColor.entries.forEach { knownColorKeys += it.key }
        // Built-in fallback keys referenced by tool bar defaults.
        knownColorKeys += "hilited_candidate_button_color"

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
                    val value = valueNode.string ?: return@forEach
                    if (!isHexColor(value)) {
                        errors += "preset_color_schemes: invalid color '$value' for '$key'"
                    }
                }
            }
        }

        sections["fallback_colors"]?.pairs?.forEach { (keyNode, valueNode) ->
            val key = keyNode.string ?: return@forEach
            knownColorKeys += key
            val value = valueNode.string ?: return@forEach
            if (!isHexColor(value) && value !in knownColorKeys) {
                errors += "fallback_colors: invalid color reference '$value' for '$key'"
            }
        }

        sections["tool_bar"]?.let { validateColorFields(it, "tool_bar", knownColorKeys, errors) }
        return errors
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

    private fun parseMapping(yaml: String): Node.Mapping? =
        try {
            Yaml.Default.parseToYamlNode(yaml).mapping
        } catch (_: Exception) {
            null
        }
}
