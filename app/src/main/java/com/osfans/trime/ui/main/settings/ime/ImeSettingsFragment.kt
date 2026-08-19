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
import com.osfans.trime.R
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.schema.PackageStore
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.settings.ImePickerDialog
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class ImeSettingsFragment : PaddingPreferenceFragment() {
    private lateinit var packageLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private var pendingExportFile: File? = null

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
                            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                                file.inputStream().use { it.copyTo(out) }
                            }
                        }
                        ctx.toast(R.string.export)
                    } catch (t: Exception) {
                        Timber.w(t, "Failed to export IME package")
                        ctx.toast(R.string.install_schema_layout_package_failure)
                    } finally {
                        file.delete()
                    }
                }
            }
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
                addPreference(
                    R.string.install_schema_layout_package,
                    R.string.install_schema_layout_package_summary,
                ) {
                    packageLauncher.launch("application/zip")
                }
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
            try {
                val file = withContext(Dispatchers.IO) {
                    ImePackageManager.exportPackage(pkg.fileName)
                }
                pendingExportFile = file
                exportLauncher.launch("${pkg.fileName.removeSuffix(".zip")}-export.zip")
            } catch (t: Exception) {
                Timber.w(t, "Failed to prepare IME package export")
                ctx.toast(R.string.install_schema_layout_package_failure)
            }
        }
    }

    private fun installImePackage(uri: Uri) {
        val ctx = requireContext()
        // No modal dialog: the compile runs in the foreground :compile service
        // and shows a notification, so the user can keep using the app.
        lifecycleScope.launch {
            val tempFile = File.createTempFile("ime-package-", ".zip", ctx.cacheDir)
            val imported =
                try {
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)!!.use { input ->
                            tempFile.outputStream().use { input.copyTo(it) }
                        }
                        ImePackageManager.importPackage(tempFile)
                    }
                } catch (t: Exception) {
                    Timber.w(t, "Failed to import IME package")
                    ctx.toast(R.string.install_schema_layout_package_failure)
                    tempFile.delete()
                    return@launch
                }
            try {
                val packageId = ImePackageManager.packageIdOf(imported)
                // Compile runs in the foreground :compile service; its
                // notification reports start/success/failure.
                ImePackageManager.compilePackageFile("$packageId.zip")
            } catch (t: Exception) {
                // Compile failures are reported by the compile service
                // notification; only log here to avoid duplicate toasts.
                Timber.w(t, "IME package compile failed")
            } finally {
                tempFile.delete()
            }
        }
    }
}
