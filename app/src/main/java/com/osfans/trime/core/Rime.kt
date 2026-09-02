/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rime JNI and instance methods
 *
 * This class is the pure engine boundary: it has no dependency on Android or on
 * the application packages. Everything it needs (options, data directories,
 * side-effect hooks) is injected via the constructor.
 *
 * @see [librime](https://github.com/rime/librime)
 */
class Rime(
    private val inputOptions: InputOptions,
    private val environment: () -> RimeEnvironment,
    private val onBeforeStart: () -> Unit = {},
    private val onDeployStart: () -> Unit = {},
) : RimeApi,
    RimeLifecycleOwner {
    private val lifecycleRegistry = RimeLifecycleRegistry()
    override val lifecycle get() = lifecycleRegistry

    override val messageFlow = messageFlow_.asSharedFlow()

    private val _uiState = MutableStateFlow(RimeUiState())
    override val uiState = _uiState.asStateFlow()

    override val isReady: Boolean
        get() = lifecycle.currentState == RimeLifecycle.State.READY

    override var schemaCached = RimeSchema(".default")
        private set

    override val statusCached: StatusProto
        get() = _uiState.value.status

    override val compositionCached: CompositionProto
        get() = _uiState.value.composition

    override val hasMenu: Boolean
        get() = _uiState.value.hasMenu

    override val paging: Boolean
        get() = _uiState.value.paging

    private val dispatcher =
        RimeDispatcher(
            object : RimeDispatcher.RimeController {
                override fun nativeStartup(fullCheck: Boolean) {
                    try {
                        startRime(fullCheck)
                    } catch (t: Throwable) {
                        // onBeforeStart()/startupRime() failed before any
                        // deploy message could arrive. Leave STARTING
                        // explicitly: the engine is not usable and the daemon
                        // must be able to observe/retry the failure instead of
                        // hanging forever.
                        Timber.e(t, "Rime startup failed")
                        lifecycleRegistry.emitState(RimeLifecycle.State.FAILED)
                    }
                    // No unconditional READY here: readiness is gated on the
                    // deploy message (see handleRimeMessage), so a failed or
                    // half-initialized engine never masquerades as ready.
                }

                override fun nativeFinalize() {
                    exitRime()
                }
            },
        )

    private var asciiSwitchTipsJob: Job? = null
    private var isNullInputType = true
    private var lastAsciiTipsText = ""
    private var pagingMode = false

    init {
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            throw IllegalStateException("Rime has already been created!")
        }
    }

    private suspend inline fun <T> withRimeContext(crossinline block: suspend () -> T): T = withContext(dispatcher) {
        block()
    }

    override suspend fun isEmpty(): Boolean = withRimeContext {
        getCurrentRimeSchema() == ".default" // 無方案
    }

    override suspend fun syncUserData(): Boolean = withRimeContext {
        syncRimeUserData()
    }

    override suspend fun processKey(
        value: Int,
        modifiers: UInt,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value, modifiers.toInt(), isVirtual)
    }

    override suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value.value, modifiers.toInt(), isVirtual)
    }

    override suspend fun simulateKeySequence(sequence: String): Boolean = withRimeContext {
        Timber.d("simulateKeySequence: $sequence")
        if (simulateRimeKeySequence(sequence)) {
            val commit = getRimeCommit()
            val input = getRimeRawInput()
            if (!commit.text.isNullOrEmpty() || input.isNotEmpty()) {
                emitResponse(commit)
                true
            } else {
                emitResponse(CommitProto(sequence))
                false
            }
        } else {
            false
        }.also { Timber.d("simulateKeySequence ${if (it) "success" else "failed"}") }
    }

    override suspend fun selectCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        selectRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        deleteRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun changeCandidatePage(backward: Boolean): Boolean = withRimeContext {
        changeRimeCandidatePage(backward).also { emitResponse() }
    }

    override suspend fun moveCursorPos(position: Int) = withRimeContext {
        setRimeCaretPos(position)
        emitResponse()
    }

    override suspend fun availableSchemata(): Array<SchemaItem> = withRimeContext { getAvailableRimeSchemaList() }

    override suspend fun enabledSchemata(): Array<SchemaItem> = withRimeContext { getSelectedRimeSchemaList() }

    override suspend fun setEnabledSchemata(schemaIds: Array<String>) = withRimeContext { selectRimeSchemas(schemaIds) }

    override suspend fun selectedSchemata(): Array<SchemaItem> = withRimeContext { getRimeSchemaList() }

    override suspend fun selectedSchemaId(): String = withRimeContext { getCurrentRimeSchema() }

    override suspend fun selectSchema(schemaId: String) = withRimeContext { selectRimeSchema(schemaId) }

    override suspend fun currentSchema(): RimeSchema = withRimeContext {
        RimeSchema(getCurrentRimeSchema())
    }

    override suspend fun commitComposition(): Boolean = withRimeContext { commitRimeComposition().also { if (it) emitResponse() } }

    override suspend fun clearComposition() = withRimeContext {
        clearRimeComposition()
        emitResponse()
    }

    override suspend fun getRawInput(): String = withRimeContext {
        getRimeRawInput()
    }

    override suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    ): Unit = withRimeContext {
        setRimeOption(option, value)
        _uiState.update { it.copy(options = it.options + (option to value)) }
    }

    override suspend fun getRuntimeOption(option: String): Boolean = withRimeContext {
        getRimeOption(option)
    }

    override suspend fun setInput(input: String): Unit = withRimeContext {
        setRimeInput(input)
        emitResponse()
    }

    override suspend fun setNullInputType(value: Boolean) = withRimeContext {
        isNullInputType = value
    }

    override suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto> = withRimeContext {
        getRimeCandidates(startIndex, limit)
    }

    override suspend fun setCandidatePagingMode(enabled: Boolean) = withRimeContext {
        pagingMode = enabled
        emitResponse()
    }

    private fun startRime(fullCheck: Boolean) {
        onBeforeStart()
        val env = environment()
        Timber.d(
            """
            Starting rime with:
            sharedDataDir: ${env.sharedDataDir}
            userDataDir: ${env.userDataDir}
            fullCheck: $fullCheck
            """.trimIndent(),
        )
        startupRime(env.sharedDataDir, env.userDataDir, env.versionName, fullCheck)
    }

    private fun processKeyInner(value: Int, modifiers: Int, isVirtual: Boolean): Boolean {
        lastAsciiTipsText = asciiTipsText(getRimeStatus())
        val handled = processRimeKey(value, modifiers)
        emitResponse()
        if (!handled) {
            emitMessage(
                RimeMessage.KeyMessage(
                    RimeMessage.KeyMessage.Data(
                        KeyValue(value),
                        KeyModifiers.of(modifiers),
                        isVirtual,
                    ),
                ),
            )
        }
        return handled
    }

    private fun asciiTipsText(status: StatusProto): String = when {
        status.isAsciiMode -> "En"
        status.schemaName.isNotEmpty() && !status.schemaName.startsWith('.') ->
            status.schemaName.take(2)
        else -> ""
    }

    private fun emitResponse(commit: CommitProto? = null) {
        val response = getRimeResponse(pagingMode)
        emitMessage(RimeMessage.CommitTextMessage(commit ?: response.commit))
        handlePreedit(response.composition)
        if (response.composition.length <= 0 && lastAsciiTipsText != asciiTipsText(response.status)) {
            showAsciiSwitchTips(response.status)
        }
        when (val candidates = response.candidates) {
            is Candidates.Paged -> emitMessage(RimeMessage.PagedCandidatesMessage(candidates))
            is Candidates.Bulk -> emitMessage(RimeMessage.BulkCandidatesMessage(candidates))
        }
        emitMessage(RimeMessage.StatusMessage(response.status))
    }

    private fun handlePreedit(composition: CompositionProto) {
        val mode = if (isNullInputType) {
            InlinePreeditStyle.DISABLE
        } else {
            inputOptions.inlinePreeditMode
        }
        val inlinePreedit = when (mode) {
            InlinePreeditStyle.DISABLE -> ""
            InlinePreeditStyle.COMPOSING_TEXT -> composition.preedit ?: ""
            InlinePreeditStyle.COMMIT_TEXT_PREVIEW -> composition.commitTextPreview ?: ""
        }
        val composition = if (mode == InlinePreeditStyle.COMPOSING_TEXT) {
            CompositionProto()
        } else {
            composition
        }
        emitMessage(RimeMessage.InlinePreeditMessage(inlinePreedit))
        emitMessage(RimeMessage.CompositionMessage(composition))
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                _uiState.update { it.copy(status = getRimeStatus()) }
                schemaCached = RimeSchema(it.data.id)
            }
            is RimeMessage.OptionMessage -> {
                // Option change won't trigger response update
                val status = getRimeStatus()
                val optionMessage = it.data
                _uiState.update { state ->
                    state.copy(
                        status = status,
                        options = state.options + (optionMessage.option to optionMessage.value),
                    )
                }
                updateSchemaCached(status)
                if (optionMessage.option == "ascii_mode") {
                    showAsciiSwitchTips(status)
                }
            }
            is RimeMessage.DeployMessage -> {
                when (it.data) {
                    RimeMessage.DeployMessage.State.Start -> {
                        _uiState.update { state -> state.copy(deployState = DeployState.Deploying) }
                        onDeployStart()
                    }
                    RimeMessage.DeployMessage.State.Success -> {
                        _uiState.update { state -> state.copy(deployState = DeployState.Success) }
                        if (lifecycle.currentState == RimeLifecycle.State.STARTING) {
                            // Hop off the librime notification thread:
                            // emitState resumes whenReady observers, some of
                            // which restart the engine, and none of that may
                            // run inline on the notification thread.
                            lifecycleScope.launch {
                                if (lifecycle.currentState == RimeLifecycle.State.STARTING) {
                                    lifecycleRegistry.emitState(RimeLifecycle.State.READY)
                                }
                            }
                        }
                    }
                    RimeMessage.DeployMessage.State.Failure -> {
                        _uiState.update { state -> state.copy(deployState = DeployState.Failure) }
                        if (lifecycle.currentState == RimeLifecycle.State.STARTING) {
                            lifecycleScope.launch {
                                if (lifecycle.currentState == RimeLifecycle.State.STARTING) {
                                    lifecycleRegistry.emitState(RimeLifecycle.State.FAILED)
                                }
                            }
                        }
                    }
                }
            }
            is RimeMessage.CompositionMessage -> {
                val composition = it.data
                _uiState.update { state -> state.copy(composition = composition) }
            }
            is RimeMessage.PagedCandidatesMessage -> {
                val paged = it.data
                _uiState.update {
                    it.copy(
                        paging = paged.hasPrevPage,
                        hasMenu = paged.candidates.isNotEmpty(),
                    )
                }
            }
            is RimeMessage.BulkCandidatesMessage -> {
                val bulk = it.data
                _uiState.update { state -> state.copy(hasMenu = bulk.candidates.isNotEmpty()) }
            }
            is RimeMessage.StatusMessage -> {
                val status = it.data
                _uiState.update { state -> state.copy(status = status) }
                updateSchemaCached(status)
            }
            else -> {}
        }
    }

    private fun updateSchemaCached(status: StatusProto) {
        val (schemaId, schemaName) = status
        // Engine response update won't send SchemaMessage, but usually update RimeStatus
        if (schemaId != schemaCached.schemaId) {
            schemaCached = RimeSchema(schemaId)
            // notify downstream consumers that schema has changed
            messageFlow_.tryEmit(
                RimeMessage.SchemaMessage(
                    SchemaItem(schemaId, schemaName),
                ),
            )
        }
    }

    private fun showAsciiSwitchTips(status: StatusProto) {
        if (!inputOptions.asciiSwitchTips) return
        val tipsText = asciiTipsText(status)
        if (tipsText.isEmpty()) return

        lastAsciiTipsText = tipsText

        val tips = CompositionProto(tipsText)
        messageFlow_.tryEmit(RimeMessage.CompositionMessage(tips))
        _uiState.update { it.copy(composition = tips) }
        asciiSwitchTipsJob?.cancel()
        asciiSwitchTipsJob = lifecycleScope.launch {
            delay(1000L)
            val ctx = getRimeContext()
            emitMessage(RimeMessage.CompositionMessage(ctx.composition))
        }
    }

    fun startup(fullCheck: Boolean = false) {
        // Atomic CAS STOPPED -> STARTING: a concurrent restartRime or
        // createSession that also observes STOPPED gets false here and
        // returns instead of double-starting (emitState would throw).
        if (!lifecycle.tryTransition(listOf(RimeLifecycle.State.STOPPED), RimeLifecycle.State.STARTING)) {
            Timber.w("Skip starting rime: not at stopped state!")
            return
        }
        // Forget the last schema id: after an engine (re)start the first
        // StatusMessage must re-emit a SchemaMessage even when the schema id
        // did not change (e.g. a package re-import/re-activation redeploys the
        // same schema), so per-schema state (toolbar override, T9
        // disambiguation data) is re-read from the workspace instead of going
        // stale until the next real schema switch.
        schemaCached = RimeSchema(".default")
        _uiState.update { it.copy(deployState = DeployState.Idle) }
        registerRimeMessageHandler(rimeMessageHandler)
        dispatcher.start(fullCheck)
    }

    fun finalize() {
        // Atomic CAS READY|FAILED -> STOPPING (see [startup]).
        if (!lifecycle.tryTransition(
                listOf(RimeLifecycle.State.READY, RimeLifecycle.State.FAILED),
                RimeLifecycle.State.STOPPING,
            )
        ) {
            Timber.w("Skip stopping rime: not at ready state!")
            return
        }
        Timber.i("Rime finalize()")
        dispatcher.stop().let {
            if (it.isNotEmpty()) {
                Timber.w("${it.size} job(s) didn't get a chance to run!")
            }
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
        unregisterRimeMessageHandler(rimeMessageHandler)
    }

    /**
     * Stable bound reference to [handleRimeMessage]. A fresh `::handleRimeMessage`
     * callable-reference is a new object every time, so registering/unregistering
     * it by identity would never match: the handler would accumulate one copy
     * per [startup] and never be removed.
     */
    private val rimeMessageHandler: (RimeMessage<*>) -> Unit = ::handleRimeMessage

    companion object {
        /**
         * Engine event stream. UI state that must never be lost (commit text,
         * key events) is emitted first in each response batch; the large buffer
         * with [BufferOverflow.DROP_OLDEST] keeps the rime thread non-blocking
         * while giving collectors ample headroom under fast typing.
         */
        private val messageFlow_ =
            MutableSharedFlow<RimeMessage<*>>(
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        private val rimeMessageHandlers = CopyOnWriteArrayList<(RimeMessage<*>) -> Unit>()

        init {
            System.loadLibrary("rime_jni")
        }

        // init
        @JvmStatic
        external fun startupRime(
            sharedDir: String,
            userDir: String,
            versionName: String,
            fullCheck: Boolean,
        )

        @JvmStatic
        external fun exitRime()

        /** Synchronously deploy a full workspace without starting the engine. */
        @JvmStatic
        external fun deployRimeWorkspace(
            sharedDir: String,
            userDir: String,
            versionName: String,
        ): Boolean

        @JvmStatic
        external fun syncRimeUserData(): Boolean

        // input
        @JvmStatic
        external fun processRimeKey(
            keycode: Int,
            mask: Int,
        ): Boolean

        @JvmStatic
        external fun commitRimeComposition(): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        // output
        @JvmStatic
        external fun getRimeCommit(): CommitProto

        @JvmStatic
        external fun getRimeContext(): ContextProto

        @JvmStatic
        external fun getRimeStatus(): StatusProto

        // runtime options
        @JvmStatic
        external fun setRimeOption(
            option: String,
            value: Boolean,
        )

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun setRimeInput(input: String)

        @JvmStatic
        external fun getRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        // testing
        @JvmStatic
        external fun simulateRimeKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRimeRawInput(): String

        @JvmStatic
        external fun setRimeCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun deleteRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun changeRimeCandidatePage(backward: Boolean): Boolean

        @JvmStatic
        external fun getAvailableRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getSelectedRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun selectRimeSchemas(schemaIds: Array<String>): Boolean

        @JvmStatic
        external fun getRimeCandidates(
            startIndex: Int,
            limit: Int,
        ): Array<CandidateProto>

        @JvmStatic
        external fun getRimeResponse(pagingMode: Boolean): RimeResponse

        @JvmStatic
        fun handleRimeMessage(
            type: Int,
            params: Array<Any>,
        ) {
            emitMessage(RimeMessage.nativeCreate(type, params))
        }

        /**
         * Dispatch a typed message to the registered handlers and the message
         * flow. Kotlin code should construct the sealed [RimeMessage] subtypes
         * directly and call this; only the C++ channel goes through
         * [handleRimeMessage].
         */
        fun emitMessage(message: RimeMessage<*>) {
            Timber.d("Handling $message")
            rimeMessageHandlers.forEach { it.invoke(message) }
            messageFlow_.tryEmit(message)
        }

        private fun registerRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            if (rimeMessageHandlers.contains(handler)) return
            rimeMessageHandlers.add(handler)
        }

        private fun unregisterRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            rimeMessageHandlers.remove(handler)
        }
    }
}
