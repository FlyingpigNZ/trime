// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.schema.SchemaLayoutManifest
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

    private fun parseMapping(yaml: String): Node.Mapping? =
        try {
            Yaml.Default.parseToYamlNode(yaml).mapping
        } catch (_: Exception) {
            null
        }
}
