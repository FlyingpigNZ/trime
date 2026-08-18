// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp

object ImePickerDialog {
    suspend fun build(
        scope: LifecycleCoroutineScope,
        context: Context,
    ): AlertDialog {
        val packages =
            withContext(Dispatchers.IO) {
                ImePackageManager.listPackages()
            }.toMutableList()
        val active = withContext(Dispatchers.IO) {
            ImePackageManager.activePackageFileName()
        }
        val adapter =
            object : ArrayAdapter<ImePackageManager.ImePackage>(
                context,
                android.R.layout.simple_list_item_1,
                packages,
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup,
                ): View {
                    val item = getItem(position) ?: return convertView ?: View(context)
                    val row =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(8), dp(8), dp(8))
                        }
                    val activeSuffix = if (item.fileName == active) "  ✓" else ""
                    val brokenSuffix = item.error?.let { "  (broken: $it)" } ?: ""
                    row.addView(
                        TextView(context).apply {
                            text = item.name + activeSuffix + brokenSuffix
                            textSize = 16f
                            layoutParams =
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        },
                    )
                    if (!ImePackageManager.isDefaultPackage(item.fileName)) {
                        row.addView(
                            ImageButton(context).apply {
                                setImageResource(R.drawable.ic_baseline_delete_24)
                                setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
                                contentDescription = context.getString(R.string.delete)
                                background = null
                                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                                setOnClickListener {
                                    scope.launch {
                                        val isActive =
                                            withContext(Dispatchers.IO) {
                                                ImePackageManager.isActivePackage(
                                                    ImePackageManager.packageFile(item.fileName),
                                                )
                                            }
                                        if (isActive) {
                                            context.toast(R.string.cannot_delete_active_ime_package)
                                            return@launch
                                        }
                                        val deleted =
                                            withContext(Dispatchers.IO) {
                                                ImePackageManager.deletePackage(item.fileName)
                                            }
                                        if (deleted) {
                                            remove(item)
                                            notifyDataSetChanged()
                                            context.toast(R.string.ime_package_deleted)
                                        } else {
                                            context.toast(R.string.install_schema_layout_package_failure)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    return row
                }
            }
        return AlertDialog
            .Builder(context)
            .apply {
                setTitle(R.string.selected_ime)
                if (packages.isEmpty()) {
                    setMessage(R.string.no_theme_to_select)
                } else {
                    setAdapter(adapter) { dialog, which ->
                        val selected = adapter.getItem(which) ?: return@setAdapter
                        scope.launch {
                            val isActive =
                                withContext(Dispatchers.IO) {
                                    ImePackageManager.isActivePackage(
                                        ImePackageManager.packageFile(selected.fileName),
                                    )
                                }
                            dialog.dismiss()
                            if (isActive) return@launch
                            scope.withLoadingDialog(context, R.string.deploy_progress) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        ImePackageManager.activate(ImePackageManager.packageFile(selected.fileName))
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
