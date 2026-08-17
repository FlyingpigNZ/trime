// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Updates `default.custom.yaml` so a newly installed schema is added to the
 * available input-method list and selected as the default (first) entry.
 */
object SchemaListUpdater {
    fun addSchema(
        customFile: File,
        schemaId: String,
    ) {
        val yaml = Yaml()
        val root = LinkedHashMap<String, Any?>()
        if (customFile.exists()) {
            val loaded = yaml.load<Any?>(customFile.readText())
            if (loaded is Map<*, *>) {
                loaded.forEach { (key, value) -> root[key.toString()] = value }
            }
        }

        val patch =
            root["patch"] as? MutableMap<String, Any?>
                ?: LinkedHashMap<String, Any?>().also { root["patch"] = it }
        val schemaList =
            patch["schema_list"] as? MutableList<Any?>
                ?: ArrayList<Any?>().also { patch["schema_list"] = it }

        schemaList.removeAll { entry ->
            (entry as? Map<*, *>)?.get("schema") == schemaId
        }
        schemaList.add(0, mapOf("schema" to schemaId))

        customFile.parentFile?.mkdirs()
        customFile.writeText(yaml.dump(root))
    }

    /**
     * Replace the schema list with exactly the given schemas. Used when
     * switching IME packages so schemas from the previous package are removed
     * from `default.custom.yaml` and no mixture remains.
     */
    fun setSchemas(
        customFile: File,
        schemaIds: List<String>,
    ) {
        val yaml = Yaml()
        val root = LinkedHashMap<String, Any?>()
        if (customFile.exists()) {
            val loaded = yaml.load<Any?>(customFile.readText())
            if (loaded is Map<*, *>) {
                loaded.forEach { (key, value) -> root[key.toString()] = value }
            }
        }

        val patch =
            root["patch"] as? MutableMap<String, Any?>
                ?: LinkedHashMap<String, Any?>().also { root["patch"] = it }
        patch["schema_list"] =
            schemaIds.distinct().map { mapOf("schema" to it) }

        customFile.parentFile?.mkdirs()
        customFile.writeText(yaml.dump(root))
    }
}
