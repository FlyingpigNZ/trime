// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string

/**
 * Manifest inside a tier-3 schema-layout package.
 *
 * The package is a zip containing the Rime schema file, one or more layout
 * YAML fragments, an optional tier-2 decoration theme, optional Rime
 * shared-data files, and optional resources (background images, fonts, etc.)
 * used by those layouts. This model is pure JVM so it can be parsed and
 * validated without touching Android.
 */
data class SchemaLayoutManifest(
    val schemaId: String,
    val name: String,
    val version: String,
    val schemaFile: String,
    val layoutFiles: List<String>,
    val defaultKeyboard: String? = null,
    /** Optional tier-2 decoration theme inside the package, e.g. `theme.yaml`. */
    val themeFile: String? = null,
    /** File paths inside the package, e.g. `backgrounds/14jian.png`. */
    val resources: List<String> = emptyList(),
    /** Rime shared-data files inside the package, e.g. `rime/default.yaml`. */
    val rimeFiles: List<String> = emptyList(),
) {
    companion object {
        fun parse(node: Node.Mapping): SchemaLayoutManifest {
            val schemaId = node["schema_id"]?.string
                ?: throw IllegalArgumentException("Missing required field 'schema_id'")
            val name = node["name"]?.string
                ?: throw IllegalArgumentException("Missing required field 'name'")
            val version = node["version"]?.string
                ?: throw IllegalArgumentException("Missing required field 'version'")
            val schemaFile = node["schema_file"]?.string
                ?: throw IllegalArgumentException("Missing required field 'schema_file'")
            val layoutFiles = node["layout_files"]?.sequence?.mapNotNull { it.string }
                ?: throw IllegalArgumentException("Missing required field 'layout_files'")
            if (layoutFiles.isEmpty()) {
                throw IllegalArgumentException("Field 'layout_files' must not be empty")
            }
            val rimeFiles = node["rime_files"]?.sequence?.mapNotNull { it.string } ?: emptyList()
            if (rimeFiles.any { !it.startsWith("rime/") }) {
                throw IllegalArgumentException("Field 'rime_files' entries must be under 'rime/'")
            }
            return SchemaLayoutManifest(
                schemaId = schemaId,
                name = name,
                version = version,
                schemaFile = schemaFile,
                layoutFiles = layoutFiles,
                defaultKeyboard = node["default_keyboard"]?.string,
                themeFile = node["theme_file"]?.string,
                resources = node["resources"]?.sequence?.mapNotNull { it.string } ?: emptyList(),
                rimeFiles = rimeFiles,
            )
        }

        fun parse(yaml: String): SchemaLayoutManifest {
            val node = Yaml.Default.parseToYamlNode(yaml)
            require(node is Node.Mapping) { "Schema layout manifest must be a YAML mapping" }
            return parse(node)
        }
    }
}
