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
 * Guard for the window-metrics estimate.
 *
 * [android.view.WindowManager.getMaximumWindowMetrics] reports the largest
 * window the app *may* be given, which is not necessarily the display the app is
 * running on: when the platform renders an app at a reduced resolution (display
 * mode / "smart resolution" / size-compat), the maximum metrics keep describing
 * the panel while `displayMetrics` describe what the app actually draws on. The
 * estimate is only there to cover the window before the container is measured,
 * and an estimate wider than the display lays every row out wider than the
 * surface, clipping the right-hand keys — so any disagreement beyond rounding
 * noise resolves to the display width, which is an upper bound for every window
 * on it. A difference within [tolerancePx] is rounding noise and is believed.
 */
internal fun resolveWindowMetricsWidth(
    windowWidth: Int,
    displayWidth: Int,
    tolerancePx: Int,
): Int = if (kotlin.math.abs(windowWidth - displayWidth) > tolerancePx) displayWidth else windowWidth
