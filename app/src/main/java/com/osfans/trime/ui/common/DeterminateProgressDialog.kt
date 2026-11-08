/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.osfans.trime.R
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.verticalMargin

/**
 * Determinate progress dialog for a long, cancellable-only-by-finishing
 * operation.
 *
 * The indicator is a Material [LinearProgressIndicator] (the app already uses
 * Material widgets on these screens) instead of a bare platform `ProgressBar`,
 * and the caption lives in the dialog's message area so it picks up the dialog
 * theme's text style. Margins match [ProgressBarDialogIndeterminate], so both
 * progress dialogs look the same at the same step of any flow.
 */
class DeterminateProgressDialog(
    private val context: Context,
    @StringRes title: Int,
    @StringRes private val progressFormat: Int,
) {
    private val indicator = LinearProgressIndicator(context).apply {
        isIndeterminate = false
        max = MAX_PROGRESS
        progress = 0
        // Pill-shaped track (the Material default in this version is square).
        trackCornerRadius = trackThickness / 2
    }

    private val content = context.verticalLayout {
        add(
            indicator,
            lParams {
                width = matchParent
                verticalMargin = dp(20)
                horizontalMargin = dp(26)
            },
        )
    }

    val dialog: AlertDialog =
        AlertDialog
            .Builder(context)
            .setTitle(title)
            .setMessage(context.getString(progressFormat, 0))
            .setView(content)
            .setCancelable(false)
            .create()

    fun update(progress: Int) {
        indicator.progress = progress
        dialog.setMessage(context.getString(progressFormat, progress))
    }

    private companion object {
        /** [indicator] range; the callback reports a percentage. */
        const val MAX_PROGRESS = 100
    }
}
