/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

/**
 * Global UI scale factor shared across the IME's independent bars and
 * windows (input bar, candidate bar, unrolled candidates, tool buttons).
 *
 * The keyboard itself scales via [Keyboard.keyboardHeight], but the bars
 * above it are separate views that derive their sizes from the theme only.
 * [Keyboard] writes the user's height-scale here on construction, and the
 * bar/window UIs read it so text and heights follow the keyboard size.
 */
object UiScale {
    /** Multiplier applied to bar/window text sizes and heights (1f = original). */
    @Volatile
    var factor: Float = 1f
        private set

    fun updateFactor(newFactor: Float) {
        if (newFactor > 0f) {
            factor = newFactor
        }
    }
}
