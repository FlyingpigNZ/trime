// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

/**
 * In-memory registry of installed schema-layout packages, keyed by Rime schema
 * id. This is the explicit replacement for relying only on the alphabet
 * heuristic in [com.osfans.trime.ime.keyboard.KeyboardSwitcher].
 */
data class SchemaLayoutRegistry(
    val packages: Map<String, SchemaLayoutManifest> = emptyMap(),
    val extraDefaultKeyboards: Map<String, String> = emptyMap(),
) {
    val defaultKeyboards: Map<String, String>
        get() {
            val result = LinkedHashMap(extraDefaultKeyboards)
            packages.forEach { (schemaId, manifest) ->
                manifest.defaultKeyboard?.let { result[schemaId] = it }
            }
            return result
        }

    fun defaultKeyboardFor(schemaId: String): String? =
        packages[schemaId]?.defaultKeyboard ?: extraDefaultKeyboards[schemaId]

    fun isEmpty(): Boolean = packages.isEmpty() && extraDefaultKeyboards.isEmpty()

    operator fun plus(other: SchemaLayoutRegistry): SchemaLayoutRegistry =
        SchemaLayoutRegistry(
            packages = packages + other.packages,
            extraDefaultKeyboards = extraDefaultKeyboards + other.extraDefaultKeyboards,
        )

    companion object {
        val Empty = SchemaLayoutRegistry()

        fun fromManifests(vararg manifests: SchemaLayoutManifest): SchemaLayoutRegistry =
            SchemaLayoutRegistry(manifests.associateBy { it.schemaId })

        /** A registry that only carries explicit schema → default-keyboard bindings. */
        fun fromDefaultKeyboards(defaultKeyboards: Map<String, String>): SchemaLayoutRegistry =
            SchemaLayoutRegistry(extraDefaultKeyboards = defaultKeyboards)
    }
}
