/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.ime

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.osfans.trime.R
import com.osfans.trime.daemon.ImePackageNotifications
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.schema.PackageStore
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.settings.ImePickerDialog
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.setup
import com.osfans.trime.util.toast
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
        }
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
