/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

/**
 * Outcome of the last engine deploy (startup maintenance, package
 * activation). UI layers can use this to surface deploy failures even though
 * the engine lifecycle itself is managed by [RimeLifecycle].
 */
enum class DeployState {
    /** No deploy has been reported yet. */
    Idle,
    /** A deploy is in progress (deploy "start" message received). */
    Deploying,
    /** The last deploy finished successfully. */
    Success,
    /** The last deploy failed; the engine may be in [RimeLifecycle.State.FAILED]. */
    Failure,
}

/**
 * Immutable snapshot of the engine state that the UI observes.
 *
 * Published as a [kotlinx.coroutines.flow.StateFlow] from [RimeApi.uiState] so
 * UI code can read the current value without blocking on the rime thread (the
 * old `rime.run { statusCached }` pattern went through `runBlocking` on the
 * caller thread and raced with engine-side writes).
 */
data class RimeUiState(
    val status: StatusProto = StatusProto(),
    val composition: CompositionProto = CompositionProto(),
    val hasMenu: Boolean = false,
    val paging: Boolean = false,
    /** UI-relevant runtime options (e.g. `_hide_key_symbol`), keyed by name. */
    val options: Map<String, Boolean> = emptyMap(),
    /** Outcome of the most recent deploy, for surfacing failures in the UI. */
    val deployState: DeployState = DeployState.Idle,
) {
    val schemaId: String get() = status.schemaId
    val schemaName: String get() = status.schemaName
    val isAsciiMode: Boolean get() = status.isAsciiMode
    val isComposing: Boolean get() = status.isComposing
    val isAsciiPunct: Boolean get() = status.isAsciiPunct
}
