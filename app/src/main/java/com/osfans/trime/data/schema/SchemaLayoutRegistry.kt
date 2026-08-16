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
) {
    val defaultKeyboards: Map<String, String>
        get() =
            packages.mapNotNull { (schemaId, manifest) ->
                manifest.defaultKeyboard?.let { schemaId to it }
            }.toMap()

    fun defaultKeyboardFor(schemaId: String): String? = packages[schemaId]?.defaultKeyboard

    fun isEmpty(): Boolean = packages.isEmpty()

    companion object {
        val Empty = SchemaLayoutRegistry()

        fun fromManifests(vararg manifests: SchemaLayoutManifest): SchemaLayoutRegistry =
            SchemaLayoutRegistry(manifests.associateBy { it.schemaId })
    }
}
