// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import android.app.ActivityManager
import android.content.Intent
import androidx.core.content.ContextCompat
import com.osfans.trime.BuildConfig
import com.osfans.trime.daemon.ImePackageNotifications
import com.osfans.trime.daemon.PackageCompileService
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.PackageThemeLoader
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs workspace compiles in the separate `:compile` process and waits for
 * `compiled.marker` / `compiled.error` / the compile timeout, with fail-fast
 * death detection via [ActivityManager.getRunningAppProcesses].
 *
 * Process liveness is a system-level fact: [ActivityManager.getRunningAppProcesses]
 * always includes same-uid processes (the main process and the `:compile`
 * process share one uid), so a live compile is present in the list and a dead
 * one is not. This is immune to the file-backed pid/heartbeat flakiness that
 * previously caused false "compile died" reports on device — no pid files, no
 * /proc parsing, no binder connections, no auto-restarted zombie processes.
 *
 * The compile service's own ground-truth files (compiled.marker / compiled.error)
 * still decide the final outcome; process liveness only accelerates the timeout
 * path when the process genuinely died.
 *
 * Split out of [ImePackageManager].
 */
internal object PackageCompiler {
    private val compiling = AtomicBoolean(false)
    private val compileMutex = Mutex()

    /** True while a compile is running; UI should block new imports. */
    fun isCompiling(): Boolean = compiling.get()

    fun isCompiled(fileName: String): Boolean = PackageStore.isCompiled(fileName.removeSuffix(".zip"))

    /** Compile an imported package in the background compile process. */
    suspend fun compilePackageFile(fileName: String) {
        val id = fileName.removeSuffix(".zip")
        PackageMetadata.requireSafePackageId(id)
        compilePackage(id)
    }

    /**
     * Mark the current Rime user data dir as compiled. Called when the engine
     * itself finishes a successful deploy (e.g. Default on first startup), so
     * the separate compile service does not redundantly compile it again.
     */
    fun markCurrentWorkspaceCompiled() {
        val workspace = DataManager.userDataDir
        val hasContent =
            File(workspace, "default.yaml").isFile ||
                File(workspace, "manifest.yaml").isFile ||
                File(workspace, "component.yaml").isFile
        if (!hasContent) return
        val active = PackageStore.activePackageId()
        val content =
            if (active == null || active == PackageStore.DEFAULT_PACKAGE_ID) {
                val source = File(DataManager.sharedDataDir, PackageStore.DEFAULT_PACKAGE_FILE_NAME)
                if (source.isFile) PackageMetadata.sha256(source) else "ok"
            } else {
                "ok"
            }
        File(workspace, "compiled.marker").writeText(content)
        File(workspace, "compiled.error").delete()
    }

    /** Reload the active package's theme after startup or activation. */
    suspend fun restoreActiveTheme() {
        val workspace = withContext(Dispatchers.IO) { PackageStore.activeWorkspaceDir() } ?: return
        val theme =
            withContext(Dispatchers.IO) {
                PackageThemeLoader.load(workspace)
            }
        applyTheme(theme)
    }

    internal suspend fun applyTheme(theme: Theme) {
        withContext(Dispatchers.Main) {
            ThemeManager.applySchemaLayout(theme, replaceTheme = true)
        }
    }

    /**
     * Compile a package workspace in the separate `:compile` process and wait
     * for `compiled.marker`.
     */
    internal suspend fun compilePackage(
        packageId: String,
        restartActive: Boolean = true,
        restoreThemeAfter: Boolean = restartActive,
    ) {
        compileMutex.withLock {
            val workspace = PackageStore.workspaceDir(packageId)
            if (!workspace.isDirectory) {
                throw IllegalArgumentException("Package workspace missing: $workspace")
            }
            if (PackageStore.isCompiled(packageId)) return@withLock
            if (!compiling.compareAndSet(false, true)) {
                throw IllegalStateException("IME package compile is already in progress")
            }
            try {
                // The whole wait runs off the main thread: getRunningAppProcesses
                // is a blocking binder IPC and must never run on the UI thread
                // (it stalled rendering by ~8s/frame on device). The caller may
                // be on the main thread (e.g. import from the settings UI).
                withContext(Dispatchers.IO) {
                    // One compile session at a time, and only after the previous
                    // session is FULLY gone: librime keeps global Deployer state
                    // per process, so a new session started before the old process
                    // exited would run its deploy against stale state and fail
                    // instantly.
                    awaitPreviousCompileProcessExit()
                    val marker = File(workspace, "compiled.marker")
                    val error = File(workspace, "compiled.error")
                    marker.delete()
                    error.delete()
                    val intent =
                        Intent(appContext, PackageCompileService::class.java).apply {
                            putExtra(PackageCompileService.EXTRA_WORKSPACE_DIR, workspace.absolutePath)
                            putExtra(
                                PackageCompileService.EXTRA_SHARED_DIR,
                                DataManager.sharedDataDir.absolutePath,
                            )
                            putExtra(PackageCompileService.EXTRA_VERSION, BuildConfig.BUILD_VERSION_NAME)
                        }
                    ContextCompat.startForegroundService(appContext, intent)
                    val deadline = System.currentTimeMillis() + COMPILE_TIMEOUT_MS
                    // Outcome is decided by the compile service's own ground-truth
                    // files (compiled.marker / compiled.error) plus the timeout.
                    // Fail-fast: if the :compile process vanishes from the system
                    // process list, it died (or crashed) mid-compile — declare
                    // death instead of waiting out the full timeout.
                    var compileProcessSeen = false
                    while (!marker.isFile && !error.isFile && System.currentTimeMillis() < deadline) {
                        val alive = isCompileProcessAlive()
                        if (alive) compileProcessSeen = true
                        if (compileProcessSeen && !alive) {
                            throw IllegalStateException("IME package compile process died: $packageId")
                        }
                        delay(COMPILE_POLL_INTERVAL_MS)
                    }
                    if (error.isFile) {
                        throw IllegalStateException("IME package compile failed: $packageId")
                    }
                    if (!marker.isFile) {
                        throw IllegalStateException("IME package compile timed out: $packageId")
                    }
                }
                // If the active package was recompiled in place, restart Rime so it
                // picks up the new workspace contents, and reload the theme so the
                // in-memory keyboard layout reflects the updated package immediately.
                // Callers that will restart/restore themselves pass false here.
                if (restartActive && PackageStore.activePackageId() == packageId) {
                    RimeDaemon.restartRime()
                    if (restoreThemeAfter) {
                        restoreActiveTheme()
                        ImePackageNotifications.notifyRefreshed(appContext)
                    }
                }
            } finally {
                compiling.set(false)
            }
        }
    }

    /**
     * Whether the `:compile` process is currently alive, via the system's own
     * process list. Same-uid processes are always visible to
     * [ActivityManager.getRunningAppProcesses], so this is a reliable
     * system-level fact — no pid files, no /proc reads, no binder.
     */
    private fun isCompileProcessAlive(): Boolean {
        val manager = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: run {
                Timber.w("PackageCompiler: no ActivityManager; assuming compile process alive")
                return true // No manager; never report death on a platform quirk.
            }
        val processes = runCatching { manager.runningAppProcesses }.getOrElse {
            Timber.w(it, "PackageCompiler: runningAppProcesses failed; assuming compile process alive")
            return true
        }
        return processes.any { it.processName == COMPILE_PROCESS_NAME }
    }

    /**
     * Wait for a previous `:compile` process to fully exit before opening a new
     * session. The session process kills itself after finishing, but the kill is
     * asynchronous; opening a new session before the old process died would
     * land the new intent in the dying process, whose librime singleton is
     * already initialized — the deploy then fails instantly.
     */
    private suspend fun awaitPreviousCompileProcessExit() {
        if (!isCompileProcessAlive()) return
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt <= COMPILE_SESSION_EXIT_WAIT_MS) {
            if (!isCompileProcessAlive()) return
            delay(COMPILE_SESSION_EXIT_POLL_MS)
        }
        Timber.w("Previous compile process did not exit within ${COMPILE_SESSION_EXIT_WAIT_MS}ms")
        // The process refuses to die; there is nothing more the main process
        // can do about it here (it cannot kill another same-uid process's
        // service without a pid). The new session will start anyway; the
        // :compile service's single-flight guard will reject any duplicate
        // request if the old process is still busy.
    }

    private const val COMPILE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val COMPILE_POLL_INTERVAL_MS = 500L

    /** How long to wait for a previous compile process to exit. */
    private const val COMPILE_SESSION_EXIT_WAIT_MS = 10 * 1000L

    /** Poll interval while waiting for a previous compile process to exit. */
    private const val COMPILE_SESSION_EXIT_POLL_MS = 100L

    /** The :compile process name (applicationId + ":compile"). */
    private val COMPILE_PROCESS_NAME: String = "${appContext.packageName}:compile"
}
