/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.daemon

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.osfans.trime.BuildConfig
import com.osfans.trime.core.Rime
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.schema.PackageStore
import timber.log.Timber
import java.io.File

/**
 * Runs in a separate `:compile` process so librime can compile a workspace
 * without touching the main process's running engine.
 *
 * librime keeps global Deployer/config state, so each compile request must run
 * in a fresh process. The service kills its own process after finishing to
 * guarantee the next compile starts clean.
 */
class PackageCompileService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Single-flight: a second compile request while one is running would
        // run two librime deploys concurrently in this process against the
        // same global Deployer state. Drop the duplicate. Do NOT call
        // stopSelf(startId) here: the duplicate carries the most recent
        // startId, so stopSelf would also stop the service (and its
        // foreground status) while the legitimate compile is still running.
        if (!compiling.compareAndSet(false, true)) {
            Timber.w("PackageCompileService: compile already in progress; dropping duplicate request")
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ImePackageNotifications.NOTIFICATION_ID,
                ImePackageNotifications.buildCompilingNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(
                ImePackageNotifications.NOTIFICATION_ID,
                ImePackageNotifications.buildCompilingNotification(this),
            )
        }
        val workspaceDir = intent?.getStringExtra(EXTRA_WORKSPACE_DIR)
        val sharedDir = intent?.getStringExtra(EXTRA_SHARED_DIR)
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: BuildConfig.BUILD_VERSION_NAME
        // H7: publish the compile-session identity so the main process can fail
        // fast when this process dies mid-compile (crash/kill) instead of
        // waiting out the full timeout, and can wait for this session to fully
        // exit before opening the next one. The pid file records this process's
        // pid plus its /proc/<pid>/stat start time; the heartbeat (mtime
        // refreshed by a watchdog thread) is a fallback while the pid file is
        // missing. Both live in the workspace next to compiled.marker/error.
        val workspace = workspaceDir?.let(::File)
        val pidFile = workspace?.let { File(it, PackageStore.COMPILE_PID_FILE) }
        val heartbeatFile = workspace?.let { File(it, PackageStore.COMPILE_HEARTBEAT_FILE) }
        runCatching {
            workspace?.let(PackageStore::writeCompileSessionRef)
            heartbeatFile?.let { file ->
                file.createNewFile()
                file.setLastModified(System.currentTimeMillis())
            }
        }
        val watchdog =
            heartbeatFile?.let { file ->
                Thread {
                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            file.setLastModified(System.currentTimeMillis())
                        } catch (_: Exception) {
                            // Storage hiccup; the main process falls back to
                            // the pid check / staleness, so just keep trying.
                        }
                        try {
                            Thread.sleep(HEARTBEAT_INTERVAL_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }.apply {
                    name = "compile-heartbeat"
                    isDaemon = true
                    start()
                }
            }
        Thread {
            var workspace: File? = null
            try {
                workspace = workspaceDir?.let(::File)
                    ?: error("missing workspace dir extra")
                if (sharedDir == null) error("missing shared dir")
                val result = compile(workspace, sharedDir, version)
                Timber.i("PackageCompileService result: $result")
                notifyFinished(success = true)
            } catch (t: Throwable) {
                Timber.e(t, "PackageCompileService failed")
                if (workspace != null) {
                    runCatching { File(workspace, "compiled.error").writeText("failed\n") }
                }
                notifyFinished(success = false)
            } finally {
                watchdog?.interrupt()
                pidFile?.delete()
                heartbeatFile?.delete()
                compiling.set(false)
                stopSelf(startId)
                // Kill the process so the next compile starts with a fresh
                // librime instance instead of reusing stale global state.
                Process.killProcess(Process.myPid())
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun compile(workspace: File, sharedDir: String, version: String): Boolean {
        if (!workspace.isDirectory) error("workspace dir missing: $workspace")
        // Theme loading does not depend on deploy output, so reject unusable
        // themes before spending time on a full Rime deploy.
        if (!ImePackageManager.hasUsableTheme(workspace)) {
            error("compiled workspace has no usable theme: $workspace")
        }
        // Synchronously deploy the workspace in this separate process. This
        // must not race a maintenance thread, so use the dedicated workspace
        // deploy entry point instead of startupRime() + deployRimeSchemaFile().
        if (!Rime.deployRimeWorkspace(sharedDir, workspace.absolutePath, version)) {
            error("failed to deploy workspace: $workspace")
        }
        val markerContent =
            if (workspace.parentFile?.name == PackageStore.DEFAULT_PACKAGE_ID) {
                val source = File(sharedDir, ImePackageManager.DEFAULT_PACKAGE_FILE_NAME)
                if (source.isFile) sha256(source) else "ok"
            } else {
                "ok"
            }
        // Write the marker atomically (temp + rename): a mid-write kill must
        // not leave a partial marker that the main process reads as a finished
        // compile.
        val marker = File(workspace, "compiled.marker")
        val markerTmp = File(workspace, "compiled.marker.tmp")
        markerTmp.writeText(markerContent)
        if (!markerTmp.renameTo(marker)) {
            marker.writeText(markerContent)
            markerTmp.delete()
        }
        File(workspace, "compiled.error").delete()
        // The compiled workspace is self-contained; the source zip is no longer
        // needed for switching and can be removed to save space.
        File(workspace.parentFile, "package.zip").delete()
        return true
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun notifyFinished(success: Boolean) {
        if (success) {
            ImePackageNotifications.notifyCompiled(this)
        } else {
            ImePackageNotifications.notifyCompileFailed(this)
        }
        // Detach the foreground notification before stopping the service so the
        // completion result stays in the notification shade instead of being
        // removed together with the foreground service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    companion object {
        /** True while a workspace compile is running in this process. */
        private val compiling = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Heartbeat refresh period; must be well below the staleness threshold. */
        private const val HEARTBEAT_INTERVAL_MS = 2 * 1000L

        const val EXTRA_WORKSPACE_DIR = "workspace_dir"
        const val EXTRA_SHARED_DIR = "shared_dir"
        const val EXTRA_VERSION = "version"
    }
}
