// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

/**
 * Pure decisions behind the width [Keyboard] lays its rows out on.
 *
 * The keys are drawn into the container view that hosts them, so that
 * container's measured width is the only value guaranteed to describe the
 * surface being drawn on. Everything the windowing system reports is an
 * *estimate* and may legitimately differ from it:
 *
 * - [android.view.WindowManager.getMaximumWindowMetrics] reports the largest
 *   window the app may be given (that is its whole purpose), which is not
 *   necessarily the window the IME currently has — and a context without a
 *   window (a service) resolves it through the *default* display, so on a
 *   device whose IME is shown on another display it describes that other
 *   display;
 * - both metrics and their insets are read once, while the window is still
 *   settling (e.g. right after boot), and can be stale afterwards.
 *
 * Laying rows out on an over-wide value draws every key proportionally wider
 * than the container and clips the right-hand keys away, so the resolution
 * order below is: measured container first, estimate only until then.
 *
 * Kept free of Android dependencies so both decisions are unit-testable.
 */

/**
 * Width to lay the keyboard's rows out on.
 *
 * A measured container width wins whenever it is usable — positive, and taken
 * in the current orientation. A width measured in the other orientation
 * describes the previous layout (the padding, split ratio and row heights all
 * change with it), so it must not be reused.
 *
 * [estimateWidth] covers only the window between building a keyboard and its
 * container being laid out for the first time.
 */
internal fun resolveKeyboardWidth(
    measuredContainerWidth: Int,
    measuredInLandscape: Boolean?,
    isLandscape: Boolean,
    estimateWidth: Int,
): Int = if (measuredContainerWidth > 0 && measuredInLandscape == isLandscape) {
    measuredContainerWidth
} else {
    estimateWidth
}

/**
 * Guard for the window-metrics estimate against stale insets.
 *
 * A [windowWidth] narrower than [displayWidth] by more than [tolerancePx]
 * cannot describe the window the app is actually configured for (its insets
 * were derived for another orientation or display), so the display width is the
 * better estimate; a difference within the tolerance is rounding noise.
 */
internal fun resolveWindowMetricsWidth(
    windowWidth: Int,
    displayWidth: Int,
    tolerancePx: Int,
): Int = if (windowWidth < displayWidth - tolerancePx) displayWidth else windowWidth
