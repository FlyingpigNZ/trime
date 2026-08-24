// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import com.osfans.trime.BuildConfig
import com.osfans.trime.TrimeApplication
import com.osfans.trime.core.InlinePreeditStyle
import com.osfans.trime.core.InputOptions
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeEnvironment
import com.osfans.trime.core.RimeLifecycle
import com.osfans.trime.core.awaitReadyOrFailed
import com.osfans.trime.core.lifecycleScope
import com.osfans.trime.core.whenReady
import com.osfans.trime.core.whenReadyOrFailed
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.util.appContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manage the singleton instance of [Rime]
 *
 * To use rime, client should call [createSession] to obtain a [RimeSession],
 * and call [destroySession] on client destroyed. Client should not leak the instance of [RimeApi],
 * and must use [RimeSession] to access rime functionalities.
 *
 * The instance of [Rime] always exists,but whether the dispatcher runs and callback works depend on clients, i.e.
 * if no clients are connected, [Rime.finalize] will be called.
 *
 * Functions are thread-safe in this class.
 *
 * Adapted from [fcitx5-android/FcitxDaemon.kt](https://github.com/fcitx5-android/fcitx5-android/blob/364afb44dcf0d9e3db3d43a21a32601b2190cbdf/app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt)
 */
object RimeDaemon {
    private val realRime by lazy {
        val prefs = AppPrefs.defaultInstance()
        Rime(
            inputOptions =
            object : InputOptions {
                override val inlinePreeditMode: InlinePreeditStyle
                    get() =
                        when (prefs.general.inlinePreeditMode.getValue()) {
                            InlinePreeditMode.DISABLE -> InlinePreeditStyle.DISABLE
                            InlinePreeditMode.COMPOSING_TEXT -> InlinePreeditStyle.COMPOSING_TEXT
                            InlinePreeditMode.COMMIT_TEXT_PREVIEW -> InlinePreeditStyle.COMMIT_TEXT_PREVIEW
                        }

                override val asciiSwitchTips: Boolean
                    get() = prefs.general.asciiSwitchTips.getValue()
            },
            environment = {
                RimeEnvironment(
                    sharedDataDir = DataManager.sharedDataDir.absolutePath,
                    userDataDir = DataManager.userDataDir.absolutePath,
                    versionName = BuildConfig.BUILD_VERSION_NAME,
                )
            },
            onBeforeStart = {
                // Sync shared assets first so Default.zip exists, then install
                // the bundled package. installBundledDefaultPackage() writes
                // default.custom.yaml itself after extraction.
                DataManager.sync()
                ImePackageManager.installBundledDefaultPackage()
            },
            onDeployStart = { OpenCCDictManager.buildOpenCCDict() },
        )
    }

    private val rimeImpl by lazy { object : RimeApi by realRime {} }

    private val sessions = mutableMapOf<String, RimeSession>()

    private val lock = ReentrantLock()

    /**
     * Set while the engine is STARTING: a restart was requested but cannot run
     * until the current deploy completes ([onRimeStateChanged] consumes it).
     * Guards against package activation being silently dropped during the
     * initial deploy (previously `finalize()` skipped because not READY).
     *
     * When a caller is waiting for a deferred restart, [pendingRestartResult]
     * propagates the outcome of the actual redeploy; without it, a STARTING
     * restart would resolve on the *current* deploy's READY before the
     * deferred restart has even begun.
     */
    private var pendingRestart = false
    private var pendingRestartResult: CompletableDeferred<Boolean>? = null

    /** Android UI: deploy/restart progress notifications. */
    private val deployNotifier = DeployNotifier(appContext, TrimeApplication.getInstance().coroutineScope)

    init {
        deployNotifier.start(realRime.messageFlow)
        realRime.lifecycle.addObserver(::onRimeStateChanged)
    }

    private fun establish(name: String) = object : RimeSession {
        private inline fun <T> ensureEstablished(block: () -> T) = if (name in sessions) {
            block()
        } else {
            throw IllegalStateException("Session $name is not established")
        }

        override val uiState
            get() = realRime.uiState

        override val isReady: Boolean
            get() = realRime.isReady

        override val messageFlow
            get() = realRime.messageFlow

        override fun <T> run(block: suspend RimeApi.() -> T): T = ensureEstablished {
            runBlocking { block(rimeImpl) }
        }

        override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T = ensureEstablished {
            realRime.lifecycle.whenReady { block(rimeImpl) }
        }

        override suspend fun <T> runOnReadyOrFailed(block: suspend RimeApi.() -> T): T = ensureEstablished {
            realRime.lifecycle.whenReadyOrFailed { block(rimeImpl) }
        }

        override fun runIfReady(block: suspend RimeApi.() -> Unit) {
            ensureEstablished {
                if (realRime.isReady) {
                    realRime.lifecycleScope.launch {
                        block(rimeImpl)
                    }
                }
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realRime.lifecycle.lifecycleScope
    }

    fun createSession(name: String): RimeSession = lock.withLock {
        if (name in sessions) {
            return@withLock sessions.getValue(name)
        }
        when (realRime.lifecycle.currentState) {
            RimeLifecycle.State.STOPPED -> realRime.startup()
            RimeLifecycle.State.FAILED -> {
                // The previous deploy failed; tear the engine down and retry
                // on the next session instead of leaving it wedged.
                realRime.finalize()
                realRime.startup()
            }
            else -> {}
        }
        val session = establish(name)
        sessions[name] = session
        return@withLock session
    }

    fun destroySession(name: String): Unit = lock.withLock {
        if (name !in sessions) {
            return
        }
        sessions -= name
        if (sessions.isEmpty()) {
            realRime.finalize()
        }
    }

    /**
     * Reuse a session for remote service
     */
    fun getFirstSessionOrNull() = sessions.firstNotNullOfOrNull { it.value }

    /**
     * Restart Rime so it re-deploys the current workspace (e.g. after package
     * activation). Suspends until the engine is READY again; returns false
     * when the deploy failed ([RimeLifecycle.State.FAILED]).
     *
     * While the engine is STARTING the restart is deferred and applied once
     * the current deploy completes — the activation must not be dropped. The
     * engine teardown itself runs off the calling thread (finalize's
     * `runBlocking` must never block the main thread), so this function is
     * suspend and safe to call from the UI.
     */
    suspend fun restartRime(fullCheck: Boolean = false): Boolean {
        val restartId = if (fullCheck) null else deployNotifier.notifyRestartStarted()
        val result = restartInternal(fullCheck)
        if (restartId != null) {
            deployNotifier.notifyRestartFinished(restartId)
        }
        return result
    }

    private suspend fun restartInternal(fullCheck: Boolean): Boolean {
        val action =
            lock.withLock {
                when (realRime.lifecycle.currentState) {
                    RimeLifecycle.State.READY -> RestartAction.Restart
                    RimeLifecycle.State.STARTING -> {
                        pendingRestart = true
                        RestartAction.Wait
                    }
                    RimeLifecycle.State.FAILED -> RestartAction.Retry
                    RimeLifecycle.State.STOPPED -> RestartAction.Start
                    RimeLifecycle.State.STOPPING -> RestartAction.WaitForStopped
                }
            }
        return when (action) {
            RestartAction.Restart,
            RestartAction.Retry,
            -> {
                // Serialize the state transition under a coroutine mutex (a
                // ReentrantLock critical section cannot contain the
                // withContext suspension point): a concurrent
                // restartRime/createSession must not observe STOPPED twice and
                // double-startup. Waiting for READY happens outside the lock.
                transitionMutex.withLock {
                    withContext(Dispatchers.IO) {
                        realRime.finalize()
                        realRime.startup(fullCheck)
                    }
                }
                realRime.lifecycle.awaitReadyOrFailed()
            }
            RestartAction.Start -> {
                transitionMutex.withLock {
                    withContext(Dispatchers.IO) { realRime.startup(fullCheck) }
                }
                realRime.lifecycle.awaitReadyOrFailed()
            }
            RestartAction.Wait -> {
                // The restart is deferred until the in-flight deploy settles;
                // await the outcome of the actual redeploy (shared between
                // concurrent waiters) instead of the current deploy's READY.
                val result =
                    lock.withLock {
                        if (!pendingRestart) {
                            pendingRestart = true
                            pendingRestartResult = CompletableDeferred()
                        }
                        pendingRestartResult!!
                    }
                result.await()
            }
            RestartAction.WaitForStopped -> {
                // A restart landing in the STOPPING window must not be
                // silently dropped: wait for the teardown to finish (the
                // engine settles on STOPPED), then retry.
                while (realRime.lifecycle.currentState == RimeLifecycle.State.STOPPING) {
                    delay(50)
                }
                restartInternal(fullCheck)
            }
            RestartAction.None -> false
        }
    }

    private enum class RestartAction { Restart, Retry, Start, Wait, WaitForStopped, None }

    /**
     * Consume a deferred restart ([pendingRestart]) once the engine settles.
     * READY/FAILED observers may fire on the librime notification thread or a
     * lifecycle-scope thread; the restart (finalize's `runBlocking` on the
     * dispatcher mutex) must never run there, so it hops to the app scope on
     * IO.
     */
    private fun onRimeStateChanged(state: RimeLifecycle.State) {
        if (state == RimeLifecycle.State.FAILED) {
            // A live session whose deploy failed gets one automatic retry;
            // repeated failures are left to the user (the deploy-failure
            // notification) to avoid a retry loop on persistent environment
            // problems. Reset once the engine reaches READY again.
            if (sessions.isNotEmpty() && failedAutoRetried.compareAndSet(false, true)) {
                TrimeApplication.getInstance().coroutineScope.launch(Dispatchers.IO) {
                    delay(FAILED_RETRY_DELAY_MS)
                    runCatching { restartRime() }
                }
            }
        } else if (state == RimeLifecycle.State.READY) {
            failedAutoRetried.set(false)
        }
        if (state != RimeLifecycle.State.READY && state != RimeLifecycle.State.FAILED) return
        val deferred =
            lock.withLock {
                if (!pendingRestart) return
                pendingRestart = false
                pendingRestartResult.also { pendingRestartResult = null }
            }
        TrimeApplication.getInstance().coroutineScope.launch(Dispatchers.IO) {
            val ok =
                runCatching {
                    transitionMutex.withLock {
                        withContext(Dispatchers.IO) {
                            realRime.finalize()
                            realRime.startup()
                        }
                    }
                    realRime.lifecycle.awaitReadyOrFailed()
                }.getOrDefault(false)
            deferred?.complete(ok)
        }
    }

    /** Serializes engine start/stop transitions across all restart paths. */
    private val transitionMutex = Mutex()

    private val failedAutoRetried = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Delay before the single automatic retry after a failed deploy. */
    private const val FAILED_RETRY_DELAY_MS = 3_000L
}
