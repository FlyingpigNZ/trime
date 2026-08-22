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
    private fun loadRoot(customFile: File): LinkedHashMap<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        if (customFile.exists()) {
            val loaded = Yaml().load<Any?>(customFile.readText())
            if (loaded is Map<*, *>) {
                loaded.forEach { (key, value) -> root[key.toString()] = value }
            }
        }
        return root
    }

    private fun patchMap(root: MutableMap<String, Any?>): LinkedHashMap<String, Any?> {
        val patch = LinkedHashMap<String, Any?>()
        (root["patch"] as? Map<*, *>)?.forEach { (key, value) -> patch[key.toString()] = value }
        root["patch"] = patch
        return patch
    }

    private fun schemaList(patch: MutableMap<String, Any?>): MutableList<Any?> {
        val list = ArrayList<Any?>()
        (patch["schema_list"] as? List<*>)?.forEach { list.add(it) }
        patch["schema_list"] = list
        return list
    }

    fun addSchema(
        customFile: File,
        schemaId: String,
    ) {
        val root = loadRoot(customFile)
        val patch = patchMap(root)
        val schemaList = schemaList(patch)

        schemaList.removeAll { entry ->
            (entry as? Map<*, *>)?.get("schema") == schemaId
        }
        schemaList.add(0, mapOf("schema" to schemaId))

        customFile.parentFile?.mkdirs()
        customFile.writeText(Yaml().dump(root))
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
        val root = loadRoot(customFile)
        val patch = patchMap(root)
        patch["schema_list"] =
            schemaIds.distinct().map { mapOf("schema" to it) }

        customFile.parentFile?.mkdirs()
        customFile.writeText(Yaml().dump(root))
    }
}
