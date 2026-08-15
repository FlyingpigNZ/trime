// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.RimeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A interface to run different operations on RimeApi
 */
interface RimeSession {
    /** Observable engine state; read [StateFlow.value] without blocking. */
    val uiState: StateFlow<RimeUiState>

    /**
     * Engine event stream (commit/composition/candidates/status/...).
     * Collect directly without going through [run].
     */
    val messageFlow: SharedFlow<RimeMessage<*>>

    /**
     * Run an operation immediately
     * The suspended [block] will be executed in caller's thread.
     * Use this function only for non-blocking operations like
     * accessing [RimeApi.messageFlow].
     */
    fun <T> run(block: suspend RimeApi.() -> T): T

    /**
     * Run an operation immediately if rime is at ready state.
     * Otherwise, caller will be suspended until rime is ready and operation is done.
     * The [block] will be executed in caller's thread.
     * Client should use this function in most cases.
     */
    suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T

    /**
     * Run an operation if rime is at ready state.
     * Otherwise, do nothing.
     * The [block] will be executed in executed in thread pool.
     * This function does not block or suspend the caller.
     */
    fun runIfReady(block: suspend RimeApi.() -> Unit)

    val lifecycleScope: CoroutineScope
}
