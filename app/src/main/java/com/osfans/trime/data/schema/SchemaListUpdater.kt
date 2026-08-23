// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Updates `default.custom.yaml` so a newly installed schema is added to the
 * available input-method list and selected as the default (first) entry.
 *
 * Only the `patch.schema_list` region is rewritten in place; every other line
 * of the file (user comments, unrelated patch keys) is kept verbatim. The
 * SnakeYAML dump path is used only as a fallback for layouts that cannot be
 * edited safely (e.g. a flow-style `patch: {...}`).
 */
object SchemaListUpdater {
    private const val PATCH_KEY = "patch"
    private const val SCHEMA_LIST_KEY = "schema_list"

    /** Indentation used when creating a new `patch.schema_list` block. */
    private const val DEFAULT_CHILD_INDENT = 2

    /** Additional indentation of one list item under `schema_list:`. */
    private const val LIST_ITEM_INDENT = 2

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

        val ids =
            schemaList.mapNotNull { entry ->
                (entry as? Map<*, *>)?.get("schema") as? String
                    ?: entry as? String
            }
        writeSchemaList(customFile, ids)
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
        writeSchemaList(customFile, schemaIds)
    }

    private fun writeSchemaList(
        customFile: File,
        schemaIds: List<String>,
    ) {
        customFile.parentFile?.mkdirs()
        val ids = schemaIds.distinct()
        if (!customFile.exists()) {
            customFile.writeText(freshFile(ids))
            return
        }
        val text = customFile.readText(Charsets.UTF_8)
        val edited = editSchemaListRegion(text, ids)
        if (edited != null) {
            customFile.writeText(edited)
        } else {
            // Layout we cannot edit safely: rewrite with SnakeYAML, accepting
            // that comments are lost in this rare case.
            fallbackRewrite(customFile, ids)
        }
    }

    private fun freshFile(ids: List<String>): String =
        (listOf("$PATCH_KEY:") + schemaListLines(DEFAULT_CHILD_INDENT, ids))
            .joinToString("\n") + "\n"

    private fun schemaListLines(
        indent: Int,
        ids: List<String>,
    ): List<String> =
        buildList {
            add(" ".repeat(indent) + "$SCHEMA_LIST_KEY:")
            ids.forEach { id ->
                add(" ".repeat(indent + LIST_ITEM_INDENT) + "- schema: $id")
            }
        }

    /**
     * Replace only the `patch.schema_list` block of [text], keeping every
     * other line verbatim. Returns null when the layout cannot be located
     * safely, so the caller can fall back to a full rewrite.
     */
    private fun editSchemaListRegion(
        text: String,
        ids: List<String>,
    ): String? {
        val trailingNewline = text.endsWith('\n')
        val lines = splitLines(text)
        val patchIndex = findRootPatchIndex(lines) ?: return null
        val childIndent = findPatchChildIndent(lines, patchIndex)
        val block = findSchemaListBlock(lines, patchIndex, childIndent)
        val result =
            if (block != null) {
                replaceLines(lines, block, schemaListLines(childIndent, ids))
            } else {
                insertSchemaList(lines, patchIndex, childIndent, ids)
            }
        return joinLines(result, trailingNewline)
    }

    private fun replaceLines(
        lines: List<String>,
        range: IntRange,
        replacement: List<String>,
    ): List<String> {
        val mutable = lines.toMutableList()
        mutable.subList(range.first, range.last + 1).clear()
        mutable.addAll(range.first, replacement)
        return mutable
    }

    private fun splitLines(text: String): List<String> =
        text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }

    private fun joinLines(
        lines: List<String>,
        trailingNewline: Boolean,
    ): String = lines.joinToString("\n") + if (trailingNewline) "\n" else ""

    /** Index of the root-level `patch:` key, or null when absent/uneditable. */
    private fun findRootPatchIndex(lines: List<String>): Int? {
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            if (line[0] == ' ') continue // indented line, not a root key
            if (line.startsWith("$PATCH_KEY:")) {
                val rest = line.substring(PATCH_KEY.length + 1)
                // Only a bare `patch:` (optionally with a trailing comment)
                // can be edited safely; a flow-style value falls back.
                if (rest.isBlank() || rest.trimStart().startsWith("#")) return i
                return null
            }
        }
        return null
    }

    /**
     * Indentation used by the direct children of the `patch` mapping. Falls
     * back to [DEFAULT_CHILD_INDENT] when the mapping is empty.
     */
    private fun findPatchChildIndent(
        lines: List<String>,
        patchIndex: Int,
    ): Int {
        for (i in patchIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { it != ' ' }
            if (indent <= 0) return DEFAULT_CHILD_INDENT // left the patch mapping
            if (line[indent] == '#') continue // comment, not a structural key
            return indent
        }
        return DEFAULT_CHILD_INDENT
    }

    /**
     * Line range of the `schema_list` block directly under `patch`, or null
     * when it is absent. The block covers the key line and its list items.
     */
    private fun findSchemaListBlock(
        lines: List<String>,
        patchIndex: Int,
        childIndent: Int,
    ): IntRange? {
        for (i in patchIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { it != ' ' }
            if (indent <= 0) return null // patch mapping ended without schema_list
            if (indent != childIndent) continue
            if (!line.substring(indent).startsWith("$SCHEMA_LIST_KEY:")) continue

            var end = i + 1
            while (end < lines.size) {
                val next = lines[end]
                if (next.isBlank()) {
                    end++
                    continue
                }
                if (next.indexOfFirst { it != ' ' } <= indent) break
                end++
            }
            while (end - 1 > i && lines[end - 1].isBlank()) end--
            return i until end
        }
        return null
    }

    /**
     * Insert a `schema_list` block at the end of the `patch` mapping (before
     * the first following root-level line, so trailing comments stay last).
     */
    private fun insertSchemaList(
        lines: List<String>,
        patchIndex: Int,
        childIndent: Int,
        ids: List<String>,
    ): List<String> {
        val insertAt =
            (patchIndex + 1 until lines.size).firstOrNull { i ->
                val line = lines[i]
                !line.isBlank() && line[0] != ' '
            } ?: lines.size
        return lines.toMutableList().apply {
            addAll(insertAt, schemaListLines(childIndent, ids))
        }
    }

    private fun fallbackRewrite(
        customFile: File,
        ids: List<String>,
    ) {
        val root = loadRoot(customFile)
        val patch = patchMap(root)
        patch[SCHEMA_LIST_KEY] = ids.map { mapOf("schema" to it) }
        customFile.writeText(Yaml().dump(root))
    }
}
