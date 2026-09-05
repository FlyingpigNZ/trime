// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

/**
 * In-memory registry of explicit schema → default-keyboard bindings.
 *
 * The active IME package declares its schema id and default keyboard in its
 * manifest; this registry exposes that binding so
 * [com.osfans.trime.ime.keyboard.KeyboardSwitcher] can resolve the correct
 * default keyboard without the legacy alphabet heuristic.
 */
data class DefaultKeyboardRegistry(
    val bindings: Map<String, String> = emptyMap(),
) {
    fun defaultKeyboardFor(schemaId: String): String? = bindings[schemaId]

    /**
     * The manifest `default_keyboard` when the active package declares exactly
     * one schema binding (the current manifest shape). Unlike reaching into
     * [bindings] with `firstOrNull()`, this stays unambiguous if a package
     * ever declares several schema → keyboard bindings.
     */
    fun singleDefaultKeyboard(): String? = bindings.values.singleOrNull()

    fun isEmpty(): Boolean = bindings.isEmpty()

    operator fun plus(other: DefaultKeyboardRegistry): DefaultKeyboardRegistry = DefaultKeyboardRegistry(
        bindings = bindings + other.bindings,
    )

    companion object {
        val Empty = DefaultKeyboardRegistry()

        /** A registry that only carries explicit schema → default-keyboard bindings. */
        fun fromDefaultKeyboards(defaultKeyboards: Map<String, String>): DefaultKeyboardRegistry = DefaultKeyboardRegistry(bindings = defaultKeyboards)
    }
}
