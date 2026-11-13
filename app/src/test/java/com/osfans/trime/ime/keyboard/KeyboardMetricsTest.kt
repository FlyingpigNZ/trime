// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Pins the record the keyboard caches compare before reusing a built keyboard.
 *
 * Each case differs in exactly one field on purpose: if a metric stops taking
 * part in the comparison, its case fails, and a cache could hand back a model
 * whose px geometry was built with numbers that are no longer in effect — the
 * "complete but shrunken keyboard" shape.
 */
class KeyboardMetricsTest :
    StringSpec({
        val base = KeyboardMetrics(densityDpi = 476, fontScale = 1f, landscapeMode = false)

        "identical metrics are reusable" {
            base shouldBe KeyboardMetrics(densityDpi = 476, fontScale = 1f, landscapeMode = false)
        }

        "a density change invalidates a built keyboard" {
            // Display-mode ("smart resolution") switch: 476 <-> 560 dpi on the
            // panel this was reported on.
            base shouldNotBe base.copy(densityDpi = 560)
        }

        "a font-scale change invalidates a built keyboard" {
            base shouldNotBe base.copy(fontScale = 1.15f)
        }

        "a landscape-mode change invalidates a built keyboard" {
            base shouldNotBe base.copy(landscapeMode = true)
        }
    })
