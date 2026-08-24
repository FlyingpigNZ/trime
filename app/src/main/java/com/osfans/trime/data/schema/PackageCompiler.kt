// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

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
 * `compiled.marker`, with fail-fast death detection. Also owns the compiled
 * marker bookkeeping ([markCurrentWorkspaceCompiled]) and theme reload
 * ([restoreActiveTheme]) shared by the compile and activation flows.
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
                val startedAt = System.currentTimeMillis()
                val deadline = startedAt + COMPILE_TIMEOUT_MS
                while (!marker.isFile && !error.isFile && System.currentTimeMillis() < deadline) {
                    if (compileProcessDied(workspace, startedAt)) {
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
     * Detect that the `:compile` process is no longer making progress before
     * the compile timeout expires. Liveness is the conjunction of the pid
     * file's `/proc/<pid>` check AND a fresh heartbeat: the pid alone can
     * false-positive when the dead process's pid gets reused by another
     * same-uid process, while the heartbeat is only refreshed by the compile
     * process's own watchdog thread.
     */
    private fun compileProcessDied(workspace: File, startedAt: Long): Boolean {
        val pidFile = File(workspace, PackageStore.COMPILE_PID_FILE)
        val pid = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull()
        val heartbeat = File(workspace, PackageStore.COMPILE_HEARTBEAT_FILE)
        if (pid != null) {
            val pidAlive = File("/proc/$pid").isDirectory
            val heartbeatFresh =
                !heartbeat.isFile ||
                    System.currentTimeMillis() - heartbeat.lastModified() <= COMPILE_HEARTBEAT_STALE_MS
            return !(pidAlive && heartbeatFresh)
        }
        if (heartbeat.isFile) {
            return System.currentTimeMillis() - heartbeat.lastModified() > COMPILE_HEARTBEAT_STALE_MS
        }
        // Neither liveness signal exists yet: the compile process may still be
        // spawning. Only give up once the grace period has passed.
        return System.currentTimeMillis() - startedAt > COMPILE_PROCESS_GRACE_MS
    }

    private const val COMPILE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val COMPILE_POLL_INTERVAL_MS = 500L

    /** Grace period for the `:compile` process to spawn and publish its pid. */
    private const val COMPILE_PROCESS_GRACE_MS = 10 * 1000L

    /** Heartbeat is considered stale after this long without a refresh (5× the 2s interval). */
    private const val COMPILE_HEARTBEAT_STALE_MS = 10 * 1000L
}
