/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode

/**
 * The configuration numbers a [Keyboard] bakes into its geometry when it is
 * constructed.
 *
 * Every dp/sp dimension — row heights, gaps, paddings, label sizes — is
 * converted with the density and font scale in effect at construction, and the
 * landscape mode decides which row-height/padding variants are used. A model
 * built under other numbers therefore describes a *different* keyboard than the
 * one the container is laid out for: drawn into the container it appears as a
 * whole keyboard at the wrong size, anchored at the container's top-left (the
 * "complete but shrunken keyboard" report).
 *
 * Comparing this record is how the caches ([KeyboardSwitcher] models,
 * [KeyboardWindow] views) notice that a cached keyboard predates a
 * configuration change — including the changes that never reached this process
 * as a callback, which is exactly why the comparison exists in addition to
 * `onConfigurationChanged`.
 */
internal data class KeyboardMetrics(
    val densityDpi: Int,
    val fontScale: Float,
    val landscapeMode: Boolean,
)

/** [KeyboardMetrics] the current configuration would produce. */
internal fun Context.currentKeyboardMetrics(): KeyboardMetrics = KeyboardMetrics(
    densityDpi = resources.configuration.densityDpi,
    fontScale = resources.configuration.fontScale,
    landscapeMode = isLandscapeMode(),
)
