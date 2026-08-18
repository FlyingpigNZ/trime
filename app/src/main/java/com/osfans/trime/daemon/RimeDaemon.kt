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
import com.osfans.trime.core.lifecycleScope
import com.osfans.trime.core.whenReady
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.util.appContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
                DataManager.sync()
                ImePackageManager.installBundledDefaultPackage()
            },
            onDeployStart = { OpenCCDictManager.buildOpenCCDict() },
        )
    }

    private val rimeImpl by lazy { object : RimeApi by realRime {} }

    private val sessions = mutableMapOf<String, RimeSession>()

    private val lock = ReentrantLock()

    /** Android UI: deploy/restart progress notifications. */
    private val deployNotifier = DeployNotifier(appContext, TrimeApplication.getInstance().coroutineScope)

    init {
        deployNotifier.start(realRime.messageFlow)
    }

    /** Show the ongoing deploy notification (used by IME package activation). */
    fun notifyDeployStart() = deployNotifier.notifyDeployStart()

    /** Show the deploy-finished notification (used by IME package activation). */
    fun notifyDeploySuccess() = deployNotifier.notifyDeploySuccess()

    /** Show the deploy-failed notification (used by IME package activation). */
    fun notifyDeployFailure() = deployNotifier.notifyDeployFailure()

    private fun establish(name: String) = object : RimeSession {
        private inline fun <T> ensureEstablished(block: () -> T) = if (name in sessions) {
            block()
        } else {
            throw IllegalStateException("Session $name is not established")
        }

        override val uiState
            get() = realRime.uiState

        override val messageFlow
            get() = realRime.messageFlow

        override fun <T> run(block: suspend RimeApi.() -> T): T = ensureEstablished {
            runBlocking { block(rimeImpl) }
        }

        override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T = ensureEstablished {
            realRime.lifecycle.whenReady { block(rimeImpl) }
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
        if (realRime.lifecycle.currentState == RimeLifecycle.State.STOPPED) {
            realRime.startup()
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
     * Restart Rime instance to deploy while keep the session
     */
    fun restartRime(fullCheck: Boolean = false) = lock.withLock {
        val restartId = if (fullCheck) null else deployNotifier.notifyRestartStarted()
        realRime.finalize()
        realRime.startup()
        if (restartId != null) {
            TrimeApplication.getInstance().coroutineScope.launch {
                realRime.lifecycle.whenReady {
                    deployNotifier.notifyRestartFinished(restartId)
                }
            }
        }
    }
}
