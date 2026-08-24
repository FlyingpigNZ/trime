// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import android.content.Intent
import android.os.Process
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
 * `compiled.marker` / `compiled.error` / the compile timeout. Also owns the
 * compiled marker bookkeeping ([markCurrentWorkspaceCompiled]) and theme
 * reload ([restoreActiveTheme]) shared by the compile and activation flows.
 *
 * Split out of [ImePackageManager].
 */
internal object PackageCompiler {
    private val compiling = AtomicBoolean(false)
    private val compileMutex = Mutex()

    /**
     * The compile session of the most recent compile, recorded while polling
     * for its marker. The session process kills itself after finishing, but
     * the kill is asynchronous: before opening a new session we wait for this
     * one to fully exit (force-killing it on timeout), so the new session
     * always starts in a fresh process with clean librime state.
     */
    @Volatile
    private var lastCompileSession: PackageStore.CompileSessionRef? = null

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
                // One compile session at a time, and only after the previous
                // session is FULLY destroyed: librime keeps global Deployer
                // state per process, so a new session started before the old
                // process exited would run its deploy against stale state and
                // fail instantly.
                awaitPreviousCompileSessionExit()
                val marker = File(workspace, "compiled.marker")
                val error = File(workspace, "compiled.error")
                marker.delete()
                error.delete()
                // Remove liveness artifacts left by a previously killed
                // compile process so they cannot be mistaken for the new one
                // (or for "no process started yet").
                File(workspace, PackageStore.COMPILE_PID_FILE).delete()
                File(workspace, PackageStore.COMPILE_HEARTBEAT_FILE).delete()
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
                // The separate liveness/death detection was removed: on this
                // device the pid file reads were unreliable and repeatedly
                // reported a live compile as dead ("process died") while the
                // :compile process was actually still running and succeeded.
                // Record the session identity so the next compile can wait for
                // this session to fully exit before starting.
                while (!marker.isFile && !error.isFile && System.currentTimeMillis() < deadline) {
                    PackageStore.readCompileSessionRef(workspace)?.let { lastCompileSession = it }
                    delay(COMPILE_POLL_INTERVAL_MS)
                }
                if (error.isFile) {
                    throw IllegalStateException("IME package compile failed: $packageId")
                }
                if (!marker.isFile) {
                    throw IllegalStateException("IME package compile timed out: $packageId")
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
     * Wait for the previous compile session to fully exit before opening a new
     * one. The session process kills itself after finishing, but the kill is
     * asynchronous; opening a new session before the old process died would
     * land the new intent in the dying process, whose librime singleton is
     * already initialized — the deploy then fails instantly. If the recorded
     * session is still alive past a bounded wait, force-kill it (same uid) so
     * the next session always starts in a fresh process.
     */
    private suspend fun awaitPreviousCompileSessionExit() {
        val previous = lastCompileSession ?: return
        lastCompileSession = null
        if (!PackageStore.isCompileSessionAlive(previous)) return
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt <= COMPILE_SESSION_EXIT_WAIT_MS) {
            if (!PackageStore.isCompileSessionAlive(previous)) return
            delay(COMPILE_SESSION_EXIT_POLL_MS)
        }
        if (PackageStore.isCompileSessionAlive(previous)) {
            Timber.w("Previous compile session ${previous.pid} did not exit; force-killing it")
            Process.killProcess(previous.pid)
        }
    }

    private const val COMPILE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val COMPILE_POLL_INTERVAL_MS = 500L

    /** How long to wait for the previous compile session to exit before force-killing it. */
    private const val COMPILE_SESSION_EXIT_WAIT_MS = 10 * 1000L

    /** Poll interval while waiting for the previous compile session to exit. */
    private const val COMPILE_SESSION_EXIT_POLL_MS = 100L
}
