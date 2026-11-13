// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the width decisions behind the keyboard layout: the measured container
 * is authoritative, the windowing-system metric is only a pre-layout estimate,
 * and a stale-inset estimate is rejected.
 */
class KeyboardWidthTest :
    StringSpec({
        "the measured container wins over an over-wide window-metrics estimate" {
            // The layout bug this guards: a 1080px-wide IME window while the
            // metrics estimate describes a larger window/display. Believing the
            // estimate draws every key proportionally wider than the container
            // and clips the right-hand keys away.
            resolveKeyboardWidth(
                measuredContainerWidth = 1080,
                measuredInLandscape = false,
                isLandscape = false,
                estimateWidth = 2400,
            ) shouldBe 1080
        }

        "the measured container wins over an under-wide estimate too" {
            resolveKeyboardWidth(
                measuredContainerWidth = 2400,
                measuredInLandscape = true,
                isLandscape = true,
                estimateWidth = 1080,
            ) shouldBe 2400
        }

        "the estimate is used until the container has been measured" {
            resolveKeyboardWidth(
                measuredContainerWidth = 0,
                measuredInLandscape = null,
                isLandscape = false,
                estimateWidth = 1080,
            ) shouldBe 1080

            resolveKeyboardWidth(
                measuredContainerWidth = 0,
                measuredInLandscape = null,
                isLandscape = true,
                estimateWidth = 2400,
            ) shouldBe 2400
        }

        "a width measured in the other orientation is not reused" {
            // Rotation changes the padding, the split ratio and the row heights,
            // so the previous orientation's measurement describes nothing.
            resolveKeyboardWidth(
                measuredContainerWidth = 1080,
                measuredInLandscape = false,
                isLandscape = true,
                estimateWidth = 2400,
            ) shouldBe 2400

            resolveKeyboardWidth(
                measuredContainerWidth = 2400,
                measuredInLandscape = true,
                isLandscape = false,
                estimateWidth = 1080,
            ) shouldBe 1080
        }

        "window metrics narrower than the display are treated as stale insets" {
            resolveWindowMetricsWidth(
                windowWidth = 2148,
                displayWidth = 2400,
                tolerancePx = 3,
            ) shouldBe 2400
        }

        "window metrics within the tolerance are believed" {
            resolveWindowMetricsWidth(
                windowWidth = 1080,
                displayWidth = 1080,
                tolerancePx = 3,
            ) shouldBe 1080

            resolveWindowMetricsWidth(
                windowWidth = 1078,
                displayWidth = 1080,
                tolerancePx = 3,
            ) shouldBe 1078
        }

        "window metrics wider than the display are clamped to the display" {
            // The case this guards: the platform renders the app at a reduced
            // resolution (display mode / size compat) while the maximum window
            // metrics keep describing the panel. Believing them lays every row
            // out wider than the surface and clips the right-hand keys away.
            resolveWindowMetricsWidth(
                windowWidth = 1272,
                displayWidth = 1080,
                tolerancePx = 3,
            ) shouldBe 1080
        }

        "a clamped estimate is what the keyboard is laid out on until measured" {
            val estimate =
                resolveWindowMetricsWidth(
                    windowWidth = 1272,
                    displayWidth = 1080,
                    tolerancePx = 3,
                )
            resolveKeyboardWidth(
                measuredContainerWidth = 0,
                measuredInLandscape = null,
                isLandscape = false,
                estimateWidth = estimate,
            ) shouldBe 1080
        }
    })
