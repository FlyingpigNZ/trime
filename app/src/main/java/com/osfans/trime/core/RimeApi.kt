/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RimeApi {
    val messageFlow: SharedFlow<RimeMessage<*>>

    /** Observable snapshot of engine state for the UI layer. */
    val uiState: StateFlow<RimeUiState>

    val isReady: Boolean

    val schemaCached: RimeSchema

    val statusCached: StatusProto

    val compositionCached: CompositionProto

    val hasMenu: Boolean

    val paging: Boolean

    suspend fun isEmpty(): Boolean

    suspend fun syncUserData(): Boolean

    suspend fun processKey(
        value: Int,
        modifiers: UInt = 0u,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun simulateKeySequence(
        sequence: String,
    ): Boolean

    suspend fun selectCandidate(idx: Int, global: Boolean): Boolean

    suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean

    suspend fun changeCandidatePage(backward: Boolean): Boolean

    suspend fun moveCursorPos(position: Int)

    suspend fun availableSchemata(): Array<SchemaItem>

    suspend fun enabledSchemata(): Array<SchemaItem>

    suspend fun setEnabledSchemata(schemaIds: Array<String>): Boolean

    suspend fun selectedSchemata(): Array<SchemaItem>

    suspend fun selectedSchemaId(): String

    suspend fun selectSchema(schemaId: String): Boolean

    suspend fun currentSchema(): RimeSchema

    suspend fun commitComposition(): Boolean

    suspend fun clearComposition()

    suspend fun getRawInput(): String

    /**
     * Raw input suffix of the composition's still-uncommitted trailing
     * segment, or null when no such segment exists. After a candidate pick
     * librime keeps the full raw input but opens a new trailing segment at
     * the consumed offset, so this returns exactly what the user still has to
     * disambiguate (for T9: the pure-digit remainder of the typed string).
     */
    suspend fun remainingInputTail(): String?

    suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    )

    suspend fun getRuntimeOption(option: String): Boolean

    /** Set the composition raw input directly (triggers re-translation, no key side effects). */
    suspend fun setInput(input: String)

    /**
     * Abort the current composition (clear, no commit) and set [input] as the
     * new raw input in one step, emitting a single response. The engine
     * re-parses [input] from an empty composition, so any previously selected
     * segments are dropped. Unlike calling [clearComposition] then [setInput],
     * no intermediate empty-composition update reaches the UI.
     */
    suspend fun clearAndSetInput(input: String)

    suspend fun setNullInputType(value: Boolean)

    suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto>

    suspend fun setCandidatePagingMode(enabled: Boolean)
}
