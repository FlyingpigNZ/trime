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
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.ui.main.settings.ImePickerDialog
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImeSettingsFragment : PaddingPreferenceFragment() {
    private lateinit var packageLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) installImePackage(uri)
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
                    lifecycleScope.launch { ImePickerDialog.build(lifecycleScope, requireContext()).show() }
                }
                addPreference(
                    R.string.install_schema_layout_package,
                    R.string.install_schema_layout_package_summary,
                ) {
                    packageLauncher.launch("application/zip")
                }
            }
        }
    }

    private fun installImePackage(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx, R.string.deploy_progress) {
            val tempFile = File.createTempFile("ime-package-", ".zip", ctx.cacheDir)
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        tempFile.outputStream().use { input.copyTo(it) }
                    }
                    val imported = ImePackageManager.importPackage(tempFile)
                    ImePackageManager.activate(imported)
                }
                ctx.toast(R.string.install_schema_layout_package_success)
            } catch (_: Exception) {
                ctx.toast(R.string.install_schema_layout_package_failure)
            } finally {
                tempFile.delete()
            }
        }
    }
}
