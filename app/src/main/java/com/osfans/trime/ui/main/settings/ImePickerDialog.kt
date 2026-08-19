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
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import timber.log.Timber

object ImePickerDialog {
    suspend fun build(
        scope: LifecycleCoroutineScope,
        context: Context,
        onExport: (ImePackageManager.ImePackage) -> Unit = {},
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
                    val compiled = ImePackageManager.isCompiled(item.fileName)
                    val notCompiledSuffix =
                        if (!compiled && item.error == null) {
                            "  (${context.getString(R.string.ime_package_not_compiled)})"
                        } else {
                            ""
                        }
                    row.alpha = if (compiled) 1f else 0.5f
                    row.addView(
                        TextView(context).apply {
                            text = item.name + activeSuffix + notCompiledSuffix + brokenSuffix
                            textSize = 16f
                            layoutParams =
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        },
                    )
                    if (!compiled && item.error == null) {
                        row.addView(
                            ImageButton(context).apply {
                                setImageResource(R.drawable.ic_baseline_refresh_reversed_24)
                                setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
                                contentDescription = context.getString(R.string.ime_package_compile)
                                background = null
                                isFocusable = false
                                isFocusableInTouchMode = false
                                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                                setOnClickListener {
                                    scope.launch {
                                        if (ImePackageManager.isBusy()) {
                                            return@launch
                                        }
                                        context.toast(R.string.ime_package_compiling_start)
                                        try {
                                            withContext(Dispatchers.IO) {
                                                ImePackageManager.compilePackageFile(item.fileName)
                                            }
                                            notifyDataSetChanged()
                                            context.toast(R.string.ime_package_compiled_switch_hint)
                                        } catch (t: Throwable) {
                                            Timber.w(t, "IME picker: compile failed for ${item.fileName}")
                                            context.toast(R.string.install_schema_layout_package_failure)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    if (!ImePackageManager.isDefaultPackage(item.fileName)) {
                        row.addView(
                            ImageButton(context).apply {
                                setImageResource(R.drawable.ic_baseline_delete_24)
                                setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
                                contentDescription = context.getString(R.string.delete)
                                background = null
                                // Prevent the button from stealing list-item clicks;
                                // it remains clickable through touch events.
                                isFocusable = false
                                isFocusableInTouchMode = false
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
                    row.addView(
                        ImageButton(context).apply {
                            setImageResource(R.drawable.ic_baseline_share_24)
                            setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
                            contentDescription = context.getString(R.string.export)
                            background = null
                            isFocusable = false
                            isFocusableInTouchMode = false
                            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                            setOnClickListener { onExport(item) }
                        },
                    )
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
                            Timber.i("IME picker: clicked ${selected.fileName}")
                            try {
                                val isActive =
                                    withContext(Dispatchers.IO) {
                                        ImePackageManager.isActivePackage(
                                            ImePackageManager.packageFile(selected.fileName),
                                        )
                                    }
                                Timber.i("IME picker: isActive=$isActive for ${selected.fileName}")
                                if (isActive) {
                                    dialog.dismiss()
                                    context.toast(R.string.ime_package_already_active)
                                    return@launch
                                }
                                if (!ImePackageManager.isCompiled(selected.fileName)) {
                                    context.toast(R.string.ime_package_not_compiled)
                                    return@launch
                                }
                                dialog.dismiss()
                                scope.launch {
                                    try {
                                        Timber.i("IME picker: activating ${selected.fileName}")
                                        withContext(Dispatchers.IO) {
                                            ImePackageManager.activate(ImePackageManager.packageFile(selected.fileName))
                                        }
                                        context.toast(R.string.ime_package_activation_success)
                                    } catch (t: Throwable) {
                                        Timber.w(t, "IME picker: activation failed for ${selected.fileName}")
                                        context.toast(R.string.install_schema_layout_package_failure)
                                    }
                                }
                            } catch (t: Throwable) {
                                Timber.w(t, "IME picker: pre-activation check failed for ${selected.fileName}")
                                dialog.dismiss()
                                context.toast(R.string.install_schema_layout_package_failure)
                            }
                        }
                    }
                }
                setNegativeButton(android.R.string.cancel, null)
            }.create()
    }
}
