/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.util.isNightMode
import com.osfans.trime.worker.BackgroundSyncWork
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import timber.log.Timber
import kotlin.system.exitProcess

/**
 * Custom Application class.
 * Application class will only be created once when the app run,
 * so you can init a "global" class here, whose methods serve other
 * classes everywhere.
 */
class TrimeApplication : Application() {
    val coroutineScope = MainScope() + CoroutineName("TrimeApplication")

    private val rimeIntentReceiver = RimeIntentReceiver()

    private fun registerBroadcastReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) {
            // Do NOT delegate to the platform uncaught-exception handler: on
            // Android that is KillApplicationHandler, which terminates the
            // process, making the crash screen and crash-loop guard below dead
            // code. Log explicitly instead so logcat still records the crash.
            @SuppressLint("DefaultUncaughtExceptionDelegation")
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                Timber.e(e, "Uncaught exception")
                val crashTime = System.currentTimeMillis()
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                val lastCrashTimePrefKey = "last_crash_time"
                val lastCrashTime = sharedPrefs.getLong(lastCrashTimePrefKey, -1L)
                sharedPrefs.edit(commit = true) {
                    putLong(lastCrashTimePrefKey, crashTime)
                }
                if (crashTime - lastCrashTime <= 10_000L) {
                    // continuous crashes within 10 seconds, maybe in a crash loop. just bail
                    exitProcess(10)
                }
                startActivity(
                    Intent(applicationContext, LogActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra(LogActivity.FROM_CRASH, true)
                        // avoid transaction overflow
                        val truncated =
                            e.stackTraceToString().let {
                                if (it.length > MAX_STACKTRACE_SIZE) {
                                    it.take(MAX_STACKTRACE_SIZE) + "<truncated>"
                                } else {
                                    it
                                }
                            }
                        putExtra(LogActivity.CRASH_STACK_TRACE, truncated)
                    },
                )
                exitProcess(10)
            }
        }
        instance = this
        // The :compile process only deploys a package workspace: it must not
        // run the one-time user-data migration (which would race the main
        // process on the same files) or start clipboard/collection/broadcast/
        // WorkManager machinery.
        val isCompileProcess = currentProcessName()?.endsWith(":compile") == true
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(
                    object : Timber.DebugTree() {
                        override fun createStackElementTag(element: StackTraceElement): String = "${super.createStackElementTag(element)}|${element.fileName}:${element.lineNumber}"

                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            super.log(
                                priority,
                                "[${Thread.currentThread().name}] ${tag?.substringBefore('|')}",
                                "${tag?.substringAfter('|')}] $message",
                                t,
                            )
                        }
                    },
                )
            } else {
                Timber.plant(
                    object : Timber.Tree() {
                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            if (priority < Log.INFO) return
                            Log.println(priority, "[${Thread.currentThread().name}]", message)
                        }
                    },
                )
            }
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val appPrefs = AppPrefs.initDefault(sharedPreferences)
            if (!isCompileProcess) {
                DataManager.migrateLegacyUserDataIfNeeded()
            }
            // record last pid for crash logs
            appPrefs.internal.pid.apply {
                val currentPid = Process.myPid()
                lastPid = getValue()
                Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
                setValue(currentPid)
            }
            if (!isCompileProcess) {
                ClipboardHelper.init(applicationContext)
                CollectionHelper.init(applicationContext)
                registerBroadcastReceiver()
                startWorkManager()
                // Compile the startup workspace (Default on a fresh install)
                // via the isolated :compile process before the engine's first
                // start; the engine start is gated on it so the main process
                // never runs a full workspace deploy itself.
                coroutineScope.launch {
                    runCatching { ImePackageManager.ensureStartupWorkspaceReady() }
                        .onFailure { t -> Timber.e(t, "Startup workspace bootstrap failed") }
                }
            }
        } catch (e: Exception) {
            e.fillInStackTrace()
            return
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            ColorManager.onSystemNightModeChange(newConfig.isNightMode())
        } catch (e: Exception) {
            Timber.w(e, "Something wrong on configuration changed")
        }
    }

    private fun startWorkManager() {
        coroutineScope.launch {
            BackgroundSyncWork.start(applicationContext)
        }
    }

    companion object {
        private var instance: TrimeApplication? = null
        private var lastPid: Int? = null

        fun getInstance() = instance ?: throw IllegalStateException("Trime application is not created!")

        fun getLastPid() = lastPid

        /** Best-effort current process name (API 33+; /proc/self/cmdline fallback). */
        private fun currentProcessName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Process.myProcessName()
        } else {
            runCatching {
                java.io.File("/proc/self/cmdline").readText().trim('\u0000').trim()
            }.getOrNull()
        }

        private const val MAX_STACKTRACE_SIZE = 128000

        /**
         * This permission is requested by com.android.shell, makes it possible to start
         * deploy from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a com.osfans.trime.action.DEPLOY
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}
