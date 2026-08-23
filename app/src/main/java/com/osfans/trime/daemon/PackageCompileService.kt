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
        // same global Deployer state. Drop the duplicate.
        if (!compiling.compareAndSet(false, true)) {
            Timber.w("PackageCompileService: compile already in progress; dropping duplicate request")
            stopSelf(startId)
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
        val sourceDir = intent?.getStringExtra(EXTRA_SOURCE_DIR)
        val targetDir = intent?.getStringExtra(EXTRA_TARGET_DIR)
        val workspaceDir = intent?.getStringExtra(EXTRA_WORKSPACE_DIR)
        val zipPath = intent?.getStringExtra(EXTRA_ZIP_PATH)
        val sharedDir = intent?.getStringExtra(EXTRA_SHARED_DIR)
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: BuildConfig.BUILD_VERSION_NAME
        Thread {
            var workspace: File? = null
            try {
                workspace = resolveWorkspace(sourceDir, targetDir, workspaceDir, zipPath)
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
                compiling.set(false)
                stopSelf(startId)
                // Kill the process so the next compile starts with a fresh
                // librime instance instead of reusing stale global state.
                Process.killProcess(Process.myPid())
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun resolveWorkspace(
        sourceDir: String?,
        targetDir: String?,
        workspaceDir: String?,
        zipPath: String?,
    ): File = when {
        zipPath != null -> {
            val imported = ImePackageManager.importPackage(File(zipPath))
            val packageId = ImePackageManager.packageIdOf(imported)
            PackageStore.workspaceDir(packageId)
        }
        workspaceDir != null -> File(workspaceDir)
        targetDir != null && sourceDir != null -> {
            val source = File(sourceDir)
            val target = File(targetDir)
            if (!source.isDirectory) error("source dir missing: $source")
            target.mkdirs()
            source.copyRecursively(target, overwrite = true)
            target
        }
        else -> error("missing required extras")
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
        File(workspace, "compiled.marker").writeText(markerContent)
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

        const val EXTRA_SOURCE_DIR = "source_dir"
        const val EXTRA_TARGET_DIR = "target_dir"
        const val EXTRA_WORKSPACE_DIR = "workspace_dir"
        const val EXTRA_ZIP_PATH = "zip_path"
        const val EXTRA_SHARED_DIR = "shared_dir"
        const val EXTRA_VERSION = "version"
    }
}
