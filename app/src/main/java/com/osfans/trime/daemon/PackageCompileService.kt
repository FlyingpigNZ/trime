/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
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
                val result = compile(workspace!!, sharedDir, version)
                Timber.i("PackageCompileService result: $result")
                notifyFinished(success = true)
            } catch (t: Throwable) {
                Timber.e(t, "PackageCompileService failed")
                if (workspace != null) {
                    runCatching { File(workspace, "compiled.error").writeText("failed\n") }
                }
                notifyFinished(success = false)
            } finally {
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
    ): File =
        when {
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text =
            if (success) {
                getString(R.string.ime_package_compiled_switch_hint)
            } else {
                getString(R.string.install_schema_layout_package_failure)
            }
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
                .setContentTitle(getString(R.string.rime_daemon))
                .setContentText(text)
                .setOngoing(false)
                .setAutoCancel(true)
                .setTimeoutAfter(5000L)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "IME package compile", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
            .setContentTitle(getString(R.string.rime_daemon))
            .setContentText(getString(R.string.ime_package_compiling_start))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ime-package-compile"
        private const val NOTIFICATION_ID = 2332
        const val EXTRA_SOURCE_DIR = "source_dir"
        const val EXTRA_TARGET_DIR = "target_dir"
        const val EXTRA_WORKSPACE_DIR = "workspace_dir"
        const val EXTRA_ZIP_PATH = "zip_path"
        const val EXTRA_SHARED_DIR = "shared_dir"
        const val EXTRA_VERSION = "version"
    }
}
