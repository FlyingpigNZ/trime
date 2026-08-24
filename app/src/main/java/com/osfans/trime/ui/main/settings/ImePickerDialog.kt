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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

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
                    val row = (convertView as? LinearLayout) ?: LinearLayout(context)
                    val holder = row.tag as? RowHolder ?: RowHolder(row).also { row.tag = it }
                    val activeSuffix = if (item.fileName == active) "  ✓" else ""
                    val brokenSuffix =
                        item.error?.let { "  ${context.getString(R.string.ime_package_broken, it)}" } ?: ""
                    val notCompiledSuffix =
                        if (!item.compiled && item.error == null) {
                            "  (${context.getString(R.string.ime_package_not_compiled)})"
                        } else {
                            ""
                        }
                    // Row label is a dynamic concatenation of the package name
                    // and status suffixes; placeholders do not apply here.
                    @Suppress("SetTextI18n")
                    holder.text.text = item.name + activeSuffix + notCompiledSuffix + brokenSuffix
                    row.alpha = if (item.compiled) 1f else 0.5f

                    val showCompile = !item.compiled && item.error == null
                    holder.compileBtn.visibility = if (showCompile) View.VISIBLE else View.GONE
                    if (showCompile) {
                        val compileStarted = AtomicBoolean(false)
                        holder.compileBtn.isEnabled = !ImePackageManager.isBusy()
                        holder.compileBtn.setOnClickListener {
                            scope.launch {
                                if (ImePackageManager.isBusy() || !compileStarted.compareAndSet(false, true)) {
                                    return@launch
                                }
                                holder.compileBtn.isEnabled = false
                                try {
                                    context.toast(R.string.ime_package_compiling_start)
                                    withContext(Dispatchers.IO) {
                                        ImePackageManager.compilePackageFile(item.fileName)
                                    }
                                    notifyDataSetChanged()
                                    context.toast(R.string.ime_package_compiled_switch_hint)
                                } catch (t: Throwable) {
                                    if (t is CancellationException) throw t
                                    Timber.w(t, "IME picker: compile failed for ${item.fileName}")
                                    context.toast(R.string.install_schema_layout_package_failure)
                                } finally {
                                    compileStarted.set(false)
                                    holder.compileBtn.isEnabled = true
                                }
                            }
                        }
                    } else {
                        holder.compileBtn.setOnClickListener(null)
                        holder.compileBtn.isEnabled = true
                    }

                    val showDelete = !ImePackageManager.isDefaultPackage(item.fileName)
                    holder.deleteBtn.visibility = if (showDelete) View.VISIBLE else View.GONE
                    if (showDelete) {
                        holder.deleteBtn.isEnabled = !ImePackageManager.isBusy()
                        holder.deleteBtn.setOnClickListener {
                            scope.launch {
                                if (ImePackageManager.isBusy()) {
                                    context.toast(R.string.ime_package_import_in_progress)
                                    return@launch
                                }
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
                    } else {
                        holder.deleteBtn.setOnClickListener(null)
                        holder.deleteBtn.isEnabled = true
                    }

                    holder.exportBtn.isEnabled = !ImePackageManager.isBusy()
                    holder.exportBtn.setOnClickListener {
                        if (ImePackageManager.isBusy()) {
                            context.toast(R.string.ime_package_import_in_progress)
                            return@setOnClickListener
                        }
                        onExport(item)
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
                            Timber.i("IME picker: clicked ${selected.fileName}")
                            try {
                                if (ImePackageManager.isBusy()) {
                                    context.toast(R.string.ime_package_import_in_progress)
                                    return@launch
                                }
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
                                        if (t is CancellationException) throw t
                                        Timber.w(t, "IME picker: activation failed for ${selected.fileName}")
                                        context.toast(R.string.install_schema_layout_package_failure)
                                    }
                                }
                            } catch (t: Throwable) {
                                if (t is CancellationException) throw t
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

    /**
     * Recycled row structure for the package list: one text label plus the
     * compile/delete/export buttons, with per-row visibility and click
     * handlers re-bound in [android.widget.ArrayAdapter.getView]. Keeps the
     * adapter from re-inflating views (and re-stat'ing the workspace) on
     * every bind.
     */
    private class RowHolder(row: LinearLayout) {
        val text: TextView
        val compileBtn: ImageButton
        val deleteBtn: ImageButton
        val exportBtn: ImageButton

        init {
            val ctx = row.context
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            text = TextView(ctx).apply {
                textSize = 16f
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            compileBtn = iconButton(ctx, R.drawable.ic_baseline_refresh_reversed_24, R.string.ime_package_compile)
            deleteBtn = iconButton(ctx, R.drawable.ic_baseline_delete_24, R.string.delete)
            exportBtn = iconButton(ctx, R.drawable.ic_baseline_share_24, R.string.export)
            row.addView(text)
            row.addView(compileBtn)
            row.addView(deleteBtn)
            row.addView(exportBtn)
        }

        private fun iconButton(
            ctx: Context,
            iconRes: Int,
            contentDescriptionRes: Int,
        ): ImageButton = ImageButton(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            contentDescription = ctx.getString(contentDescriptionRes)
            background = null
            // Keep buttons from stealing list-item clicks; they remain
            // clickable through touch events.
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
    }
}
