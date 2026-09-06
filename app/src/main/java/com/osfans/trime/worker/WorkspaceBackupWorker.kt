/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.schema.PackageArchive
import com.osfans.trime.data.schema.PackageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * File-name and pruning policy for scheduled workspace backups.
 *
 * Pure (JVM-testable) decisions. Timestamps are fixed width so that, for one
 * package id, lexicographic order equals chronological order.
 */
object WorkspaceBackupFiles {
    const val FILE_PREFIX = "trime-backup"

    private const val FILE_EXTENSION = ".zip"
    private const val TIMESTAMP_FORMAT = "yyyyMMdd-HHmmss"

    /**
     * Thread-local formatter: [SimpleDateFormat] is not thread-safe and
     * `ThreadLocal.withInitial` is API 26+, so use an anonymous subclass whose
     * `initialValue` works on every supported API level.
     */
    private val timestampFormatter =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

    /** e.g. `trime-backup-Default-20260101-000000.zip` (UTC timestamp). */
    fun fileName(
        packageId: String,
        timestampMillis: Long,
    ): String {
        val stamp = requireNotNull(timestampFormatter.get()).format(Date(timestampMillis))
        return "$FILE_PREFIX-$packageId-$stamp$FILE_EXTENSION"
    }

    fun isBackupOf(
        packageId: String,
        name: String,
    ): Boolean = name.startsWith("$FILE_PREFIX-$packageId-") && name.endsWith(FILE_EXTENSION)

    /**
     * Names to delete once the newest [retention] copies are kept.
     * A retention below 1 is treated as "keep nothing deleted" (defensive).
     */
    fun namesToPrune(
        names: Collection<String>,
        packageId: String,
        retention: Int,
    ): List<String> {
        val newestFirst = names.filter { isBackupOf(packageId, it) }.sortedDescending()
        return if (retention >= 1) newestFirst.drop(retention) else emptyList()
    }
}

/**
 * One-shot whole-workspace backup into the user-chosen SAF folder: a zip
 * snapshot that includes user data and excludes compile artifacts — the same
 * rule as the manual package export ([PackageArchive.exportPackage]).
 *
 * Shared by the periodic [WorkspaceBackupWorker] and the keyboard "同步" key /
 * `SYNC_USER_DATA` command (both now drive this single code path), so the
 * behavior and the reported "last backup" state stay consistent.
 *
 * No network constraint/access is involved anywhere; the app strips the
 * `ACCESS_NETWORK_STATE` declaration WorkManager's own manifest would
 * otherwise merge into the APK (see app/src/main/AndroidManifest.xml).
 */
object WorkspaceBackupRunner {
    enum class Outcome {
        /** Backup was written to the selected folder. */
        SUCCESS,

        /** No SAF backup folder has been configured yet, or its URI is no
         *  longer openable. */
        NOT_CONFIGURED,

        /** Workspace is missing or export failed. */
        FAILURE,
    }

    /**
     * Run one backup now. Exceptions are not swallowed here: periodic
     * scheduling maps them to WorkManager retries, interactive triggers wrap
     * them into a failure toast.
     *
     * Serialized because the periodic worker and the keyboard Sync key can
     * both trigger a run, and two overlapping runs would race on the
     * same-second file name and on pruning.
     */
    @Synchronized
    fun runOnce(context: Context): Outcome {
        val profile = AppPrefs.defaultInstance().profile
        if (profile.workspaceBackupTreeUri.getValue().isBlank()) {
            return Outcome.NOT_CONFIGURED
        }
        val packageId = PackageStore.activePackageId() ?: PackageStore.DEFAULT_PACKAGE_ID
        val workspace = PackageStore.workspaceDir(packageId)
        if (!workspace.isDirectory) {
            Timber.w("Workspace backup skipped: workspace missing for $packageId")
            markLast(profile, success = false)
            return Outcome.FAILURE
        }
        val root =
            DocumentFile.fromTreeUri(context, Uri.parse(profile.workspaceBackupTreeUri.getValue()))
                ?: return Outcome.NOT_CONFIGURED
        val retention = profile.workspaceBackupRetention.getValue().coerceAtLeast(1)

        return try {
            val temp = PackageArchive.exportPackage("$packageId.zip")
            try {
                val name = WorkspaceBackupFiles.fileName(packageId, System.currentTimeMillis())
                val target =
                    root.createFile("application/zip", name)
                        ?: throw IOException("Cannot create backup file in selected folder")
                context.contentResolver.openOutputStream(target.uri)?.use { out ->
                    temp.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IOException("Cannot open output stream for $name")
                prune(root, packageId, retention)
                markLast(profile, success = true)
                Timber.i("Workspace backup written: ${target.uri}")
                Outcome.SUCCESS
            } finally {
                temp.delete()
            }
        } catch (e: Exception) {
            markLast(profile, success = false)
            throw e
        }
    }

    private fun prune(
        root: DocumentFile,
        packageId: String,
        retention: Int,
    ) {
        val children = root.listFiles()
        val byName =
            children
                .mapNotNull { child -> child.name?.let { it to child } }
                .toMap()
        WorkspaceBackupFiles
            .namesToPrune(byName.keys, packageId, retention)
            .forEach { name ->
                byName[name]?.let {
                    if (it.delete()) Timber.i("Pruned old backup: $name")
                }
            }
    }

    private fun markLast(
        profile: AppPrefs.Profile,
        success: Boolean,
    ) {
        profile.lastWorkspaceBackupTime.setValue(System.currentTimeMillis())
        profile.lastWorkspaceBackupStatus.setValue(success)
    }
}

/**
 * Periodically drives [WorkspaceBackupRunner.runOnce] when the user enabled
 * the scheduled workspace backup. The worker itself imposes no network
 * constraint; see the manifest note above.
 */
class WorkspaceBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val profile = AppPrefs.defaultInstance().profile
        if (!profile.workspaceBackupEnabled.getValue()) {
            // Feature disabled while a job was queued: stop the loop.
            cancelSchedule(applicationContext)
            return@withContext Result.success()
        }
        try {
            when (WorkspaceBackupRunner.runOnce(applicationContext)) {
                WorkspaceBackupRunner.Outcome.SUCCESS -> Result.success()
                WorkspaceBackupRunner.Outcome.NOT_CONFIGURED -> {
                    cancelSchedule(applicationContext)
                    Result.success()
                }
                WorkspaceBackupRunner.Outcome.FAILURE -> Result.failure()
            }
        } catch (e: Exception) {
            Timber.e(e, "Workspace backup job failed")
            when (e) {
                is IOException -> Result.retry()
                is SecurityException -> {
                    // The SAF grant is gone (user revoked it or app data was
                    // cleared): stop looping on an unfulfillable job.
                    cancelSchedule(applicationContext)
                    Result.failure()
                }
                else -> Result.failure()
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "workspace_scheduled_backup"

        /** Minimum supported interval: 1 hour. */
        const val MIN_INTERVAL_HOURS = 1

        /** Maximum supported interval: 30 days. */
        const val MAX_INTERVAL_HOURS = 24 * 30

        /** Reconcile the WorkManager schedule with the current preferences. */
        fun syncSchedule(context: Context) {
            val profile = AppPrefs.defaultInstance().profile
            val enabled = profile.workspaceBackupEnabled.getValue()
            val folder = profile.workspaceBackupTreeUri.getValue()
            if (!enabled || folder.isBlank()) {
                cancelSchedule(context)
                return
            }
            val hours =
                profile.workspaceBackupIntervalHours
                    .getValue()
                    .coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
            val request =
                PeriodicWorkRequestBuilder<WorkspaceBackupWorker>(
                    hours.toLong(),
                    TimeUnit.HOURS,
                ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            Timber.i("Scheduled workspace backup every $hours hours")
        }

        fun cancelSchedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Timber.i("Workspace backup schedule canceled")
        }
    }
}
