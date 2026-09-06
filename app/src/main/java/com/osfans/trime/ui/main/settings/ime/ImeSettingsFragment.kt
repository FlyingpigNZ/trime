/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.ime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.daemon.ImePackageNotifications
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.schema.PackageStore
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.settings.EditTextIntPreference
import com.osfans.trime.ui.main.settings.ImePickerDialog
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.customFormatTimeInDefault
import com.osfans.trime.util.setup
import com.osfans.trime.util.toast
import com.osfans.trime.worker.WorkspaceBackupWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class ImeSettingsFragment : PaddingPreferenceFragment() {
    private lateinit var packageLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private var pendingExportFile: File? = null
    private var importPreference: Preference? = null
    private var importInProgress = false

    private val prefs = AppPrefs.defaultInstance().profile

    private val onWorkspaceBackupIntervalChanged =
        PreferenceDelegate.OnChangeListener<Int> { _, _ ->
            if (isAdded && prefs.workspaceBackupEnabled.getValue()) {
                WorkspaceBackupWorker.syncSchedule(requireContext())
            }
        }

    private val onLastWorkspaceBackupTimeChanged =
        PreferenceDelegate.OnChangeListener<Long> { _, _ ->
            if (isAdded) refreshWorkspaceBackupViews()
        }

    private val onLastWorkspaceBackupStatusChanged =
        PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
            if (isAdded) refreshWorkspaceBackupViews()
        }

    private lateinit var backupEnabledPreference: SwitchPreferenceCompat
    private lateinit var backupIntervalPreference: EditTextIntPreference
    private lateinit var backupRetentionPreference: Preference
    private lateinit var backupFolderPreference: Preference
    private lateinit var backupTreeLauncher: ActivityResultLauncher<Uri?>

    companion object {
        private const val MIN_RETENTION = 1
        private const val MAX_RETENTION = 5
        private const val LARGE_WORKSPACE_WARNING_MB = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) installImePackage(uri)
        }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                val file = pendingExportFile ?: return@registerForActivityResult
                pendingExportFile = null
                val ctx = requireContext()
                if (uri == null) {
                    file.delete()
                    return@registerForActivityResult
                }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val out =
                                ctx.contentResolver.openOutputStream(uri)
                                    ?: throw IllegalStateException("SAF returned no output stream for $uri")
                            out.use { file.inputStream().use { it.copyTo(out) } }
                        }
                        ctx.toast(R.string.export)
                    } catch (t: Exception) {
                        if (t is CancellationException) throw t
                        Timber.w(t, "Failed to export IME package")
                        ctx.toast(R.string.install_schema_layout_package_failure)
                    } finally {
                        file.delete()
                    }
                }
            }
        backupTreeLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                onBackupTreePicked(uri)
            }
        prefs.workspaceBackupIntervalHours.registerOnChangeListener(onWorkspaceBackupIntervalChanged)
        prefs.lastWorkspaceBackupTime.registerOnChangeListener(onLastWorkspaceBackupTimeChanged)
        prefs.lastWorkspaceBackupStatus.registerOnChangeListener(onLastWorkspaceBackupStatusChanged)
    }

    override fun onResume() {
        super.onResume()
        refreshImportPreference()
    }

    private fun refreshImportPreference() {
        importPreference?.isEnabled = !importInProgress && !ImePackageManager.isBusy()
        importPreference?.setSummary(
            if (importInProgress || ImePackageManager.isBusy()) {
                R.string.ime_package_import_in_progress
            } else {
                R.string.install_schema_layout_package_summary
            },
        )
    }

    override fun onDestroy() {
        prefs.workspaceBackupIntervalHours.unregisterOnChangeListener(onWorkspaceBackupIntervalChanged)
        prefs.lastWorkspaceBackupTime.unregisterOnChangeListener(onLastWorkspaceBackupTimeChanged)
        prefs.lastWorkspaceBackupStatus.unregisterOnChangeListener(onLastWorkspaceBackupStatusChanged)
        pendingExportFile?.delete()
        pendingExportFile = null
        super.onDestroy()
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(
                    R.string.selected_ime,
                    R.string.selected_ime_summary,
                ) {
                    lifecycleScope.launch {
                        ImePickerDialog.build(
                            lifecycleScope,
                            requireContext(),
                            onExport = { pkg -> startExport(pkg) },
                        ).show()
                    }
                }
                val importPref =
                    Preference(requireContext()).apply {
                        isEnabled = !importInProgress && !ImePackageManager.isBusy()
                        setup(
                            requireContext().getString(R.string.install_schema_layout_package),
                            requireContext().getString(R.string.install_schema_layout_package_summary),
                        ) {
                            if (!importInProgress && !ImePackageManager.isBusy()) {
                                packageLauncher.launch("application/zip")
                            }
                        }
                    }
                addPreference(importPref)
                importPreference = importPref
                if (PackageStore.workspaceDir(PackageStore.MIGRATED_PACKAGE_ID).isDirectory) {
                    addPreference(
                        R.string.export_migrated_workspace,
                        R.string.export_migrated_workspace_summary,
                    ) {
                        startExport(
                            ImePackageManager.ImePackage(
                                fileName = "${PackageStore.MIGRATED_PACKAGE_ID}.zip",
                                name = PackageStore.MIGRATED_PACKAGE_ID,
                                version = null,
                            ),
                        )
                    }
                }
            }
            addCategory(R.string.workspace_backup_category) {
                isIconSpaceReserved = false
                backupEnabledPreference =
                    SwitchPreferenceCompat(requireContext()).apply {
                        key = AppPrefs.Profile.WORKSPACE_BACKUP_ENABLED
                        isIconSpaceReserved = false
                        setTitle(R.string.workspace_backup_enable)
                        setDefaultValue(false)
                        setOnPreferenceChangeListener { _, newValue ->
                            handleWorkspaceBackupToggle(newValue as? Boolean ?: false)
                            false
                        }
                    }
                addPreference(backupEnabledPreference)
                backupIntervalPreference =
                    EditTextIntPreference(requireContext()).apply {
                        key = AppPrefs.Profile.WORKSPACE_BACKUP_INTERVAL_HOURS
                        isIconSpaceReserved = false
                        setTitle(R.string.workspace_backup_interval)
                        min = WorkspaceBackupWorker.MIN_INTERVAL_HOURS
                        max = WorkspaceBackupWorker.MAX_INTERVAL_HOURS
                        unit = getString(R.string.workspace_backup_hours_unit)
                        setDefaultValue(AppPrefs.Profile.DEFAULT_WORKSPACE_BACKUP_INTERVAL_HOURS)
                        summaryProvider = EditTextIntPreference.SimpleSummaryProvider
                    }
                addPreference(backupIntervalPreference)
                backupRetentionPreference =
                    Preference(requireContext()).apply {
                        isIconSpaceReserved = false
                        setTitle(R.string.workspace_backup_retention_title)
                        setOnPreferenceClickListener {
                            showRetentionDialog()
                            true
                        }
                    }
                addPreference(backupRetentionPreference)
                backupFolderPreference =
                    Preference(requireContext()).apply {
                        isIconSpaceReserved = false
                        setTitle(R.string.workspace_backup_folder)
                        setOnPreferenceClickListener {
                            backupTreeLauncher.launch(null)
                            true
                        }
                    }
                addPreference(backupFolderPreference)
            }
        }
        refreshWorkspaceBackupViews()
    }

    private fun handleWorkspaceBackupToggle(enabled: Boolean) {
        val ctx = requireContext()
        if (!enabled) {
            prefs.workspaceBackupEnabled.setValue(false)
            backupEnabledPreference.isChecked = false
            WorkspaceBackupWorker.cancelSchedule(ctx)
            refreshWorkspaceBackupViews()
            return
        }
        if (prefs.workspaceBackupTreeUri.getValue().isBlank()) {
            ctx.toast(R.string.workspace_backup_folder_required)
            refreshWorkspaceBackupViews()
            return
        }
        lifecycleScope.launch {
            val sizeMb = workspaceSizeMb()
            val commit =
                {
                    prefs.workspaceBackupEnabled.setValue(true)
                    backupEnabledPreference.isChecked = true
                    WorkspaceBackupWorker.syncSchedule(ctx)
                    refreshWorkspaceBackupViews()
                }
            if (sizeMb > LARGE_WORKSPACE_WARNING_MB) {
                AlertDialog
                    .Builder(ctx)
                    .setTitle(R.string.workspace_backup_size_warning_title)
                    .setMessage(
                        ctx.getString(
                            R.string.workspace_backup_size_warning,
                            ctx.getString(R.string.workspace_backup_size_mb, sizeMb),
                        ),
                    )
                    .setPositiveButton(android.R.string.ok) { _, _ -> commit() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                commit()
            }
        }
    }

    private fun onBackupTreePicked(uri: Uri?) {
        if (uri == null) return
        val ctx = requireContext()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val displayName =
            DocumentFile.fromTreeUri(ctx, uri)?.name
                ?: uri.lastPathSegment
                ?: uri.toString()
        prefs.workspaceBackupTreeUri.setValue(uri.toString())
        prefs.workspaceBackupDirName.setValue(displayName)
        refreshWorkspaceBackupViews()
        if (prefs.workspaceBackupEnabled.getValue()) {
            WorkspaceBackupWorker.syncSchedule(ctx)
        }
    }

    private fun showRetentionDialog() {
        val ctx = requireContext()
        val current =
            prefs.workspaceBackupRetention
                .getValue()
                .coerceIn(MIN_RETENTION, MAX_RETENTION)
        AlertDialog
            .Builder(ctx)
            .setTitle(R.string.workspace_backup_retention_title)
            .setSingleChoiceItems(
                (MIN_RETENTION..MAX_RETENTION)
                    .map { getString(R.string.workspace_backup_retention_summary, it) }
                    .toTypedArray(),
                current - MIN_RETENTION,
            ) { dialog, which ->
                val count = MIN_RETENTION + which
                prefs.workspaceBackupRetention.setValue(count)
                refreshWorkspaceBackupViews()
                if (prefs.workspaceBackupEnabled.getValue()) {
                    WorkspaceBackupWorker.syncSchedule(ctx)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshWorkspaceBackupViews() {
        if (!this::backupEnabledPreference.isInitialized) return
        val enabled = prefs.workspaceBackupEnabled.getValue()
        val lastTime = prefs.lastWorkspaceBackupTime.getValue()
        backupEnabledPreference.summary =
            if (enabled) {
                if (lastTime != 0L) {
                    getString(
                        R.string.workspace_backup_last,
                        customFormatTimeInDefault("yyyy-MM-dd HH:mm", lastTime),
                        getString(
                            if (prefs.lastWorkspaceBackupStatus.getValue()) {
                                R.string.success
                            } else {
                                R.string.failure
                            },
                        ),
                    )
                } else {
                    getString(R.string.workspace_backup_last, "N/A", "N/A")
                }
            } else {
                ""
            }
        backupIntervalPreference.isEnabled = enabled
        backupRetentionPreference.summary =
            getString(
                R.string.workspace_backup_retention_summary,
                prefs.workspaceBackupRetention.getValue().coerceIn(MIN_RETENTION, MAX_RETENTION),
            )
        backupRetentionPreference.isEnabled = enabled
        backupFolderPreference.summary =
            prefs.workspaceBackupDirName
                .getValue()
                .ifBlank { getString(R.string.workspace_backup_folder_unset) }
        // The folder is a prerequisite for enabling the switch, so it must
        // stay tappable even while the feature is off (otherwise a fresh
        // install can never select one: enabling requires a folder first).
        backupFolderPreference.isEnabled = true
    }

    private suspend fun workspaceSizeMb(): Double = withContext(Dispatchers.IO) {
        val dir = DataManager.userDataDir
        if (!dir.isDirectory) {
            return@withContext 0.0
        }
        val totalBytes =
            dir
                .walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        totalBytes / (1024.0 * 1024.0)
    }

    private fun startExport(pkg: ImePackageManager.ImePackage) {
        val ctx = requireContext()
        lifecycleScope.launch {
            var file: File? = null
            try {
                file = withContext(Dispatchers.IO) {
                    ImePackageManager.exportPackage(pkg.fileName).also { file = it }
                }
                pendingExportFile = file
                exportLauncher.launch("${pkg.fileName.removeSuffix(".zip")}-export.zip")
            } catch (t: Exception) {
                if (t is CancellationException) {
                    file?.delete()
                    throw t
                }
                Timber.w(t, "Failed to prepare IME package export")
                ctx.toast(R.string.install_schema_layout_package_failure)
            }
        }
    }

    private fun installImePackage(uri: Uri) {
        val ctx = requireContext()
        if (importInProgress || ImePackageManager.isBusy()) return
        importInProgress = true
        refreshImportPreference()
        // Surface the import phase immediately; the compile service will take
        // over the same notification when it starts.
        ImePackageNotifications.notifyImporting(ctx)
        // No modal dialog: the compile runs in the foreground :compile service
        // and shows a notification, so the user can keep using the app.
        lifecycleScope.launch {
            val tempFile = File.createTempFile("ime-package-", ".zip", ctx.cacheDir)
            var imported: File? = null
            try {
                val packageId =
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)!!.use { input ->
                            tempFile.outputStream().use { input.copyTo(it) }
                        }
                        val importedFile = ImePackageManager.importPackage(tempFile)
                        imported = importedFile
                        ImePackageManager.packageIdOf(importedFile)
                    }
                // Compile runs in the foreground :compile service; its
                // notification reports start/success/failure.
                ImePackageManager.compilePackageFile("$packageId.zip")
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                Timber.w(t, "IME package import/compile failed")
                if (imported == null) {
                    ImePackageNotifications.cancel(ctx)
                } else {
                    // Compile failures are reported by the compile service
                    // notification; update it here too in case the main process
                    // gave up on a timeout before the service could report.
                    ImePackageNotifications.notifyCompileFailed(ctx)
                }
                ctx.toast(R.string.install_schema_layout_package_failure)
            } finally {
                tempFile.delete()
                importInProgress = false
                refreshImportPreference()
            }
        }
    }
}
