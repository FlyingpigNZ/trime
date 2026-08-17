// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImePickerDialog {
    suspend fun build(
        scope: LifecycleCoroutineScope,
        context: Context,
    ): AlertDialog {
        val packages =
            withContext(Dispatchers.IO) {
                ImePackageManager.listPackages()
            }
        val active = withContext(Dispatchers.IO) {
            ImePackageManager.activePackageFileName()
        }
        val selectedIndex = packages.indexOfFirst { it.fileName == active }
        return AlertDialog
            .Builder(context)
            .apply {
                setTitle(R.string.selected_ime)
                if (packages.isEmpty()) {
                    setMessage(R.string.no_theme_to_select)
                } else {
                    setSingleChoiceItems(
                        packages.map { it.name }.toTypedArray(),
                        selectedIndex,
                    ) { dialog, which ->
                        val selected = packages[which]
                        scope.launch {
                            val isActive =
                                withContext(Dispatchers.IO) {
                                    ImePackageManager.isActivePackage(ImePackageManager.packageFile(selected.fileName))
                                }
                            dialog.dismiss()
                            if (isActive) return@launch
                            scope.withLoadingDialog(context, R.string.deploy_progress) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        ImePackageManager.activate(ImePackageManager.packageFile(selected.fileName))
                                        ThemeManager.prefs.selectedIme.setValue(selected.fileName)
                                    }
                                    context.toast(R.string.install_schema_layout_package_success)
                                } catch (t: Throwable) {
                                    context.toast(R.string.install_schema_layout_package_failure)
                                }
                            }
                        }
                    }
                }
                setNegativeButton(android.R.string.cancel, null)
            }.create()
    }
}
