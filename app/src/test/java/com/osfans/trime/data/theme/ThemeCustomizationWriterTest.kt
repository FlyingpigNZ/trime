// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.yaml.Node
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ThemeCustomizationWriterTest :
    StringSpec({
        "updatedColorSchemeBackground sets, changes and removes a per-mode background" {
            var overlay: Node.Mapping? = null
            overlay =
                ThemeCustomization.updatedColorSchemeBackground(
                    overlay,
                    "default/dark_temple",
                    ThemeCustomization.MODE_DARK,
                    "custom_default_dark_temple_night.png",
                )
            background(overlay, "default/dark_temple", ThemeCustomization.MODE_DARK) shouldBe
                "custom_default_dark_temple_night.png"

            overlay =
                ThemeCustomization.updatedColorSchemeBackground(
                    overlay,
                    "default/dark_temple",
                    ThemeCustomization.MODE_LIGHT,
                    "custom_default_dark_temple_day.png",
                )
            background(overlay, "default/dark_temple", ThemeCustomization.MODE_LIGHT) shouldBe
                "custom_default_dark_temple_day.png"

            overlay =
                ThemeCustomization.updatedColorSchemeBackground(
                    overlay,
                    "default/dark_temple",
                    ThemeCustomization.MODE_DARK,
                    null,
                )
            background(overlay, "default/dark_temple", ThemeCustomization.MODE_DARK).shouldBeNull()
            background(overlay, "default/dark_temple", ThemeCustomization.MODE_LIGHT) shouldBe
                "custom_default_dark_temple_day.png"

            overlay =
                ThemeCustomization.updatedColorSchemeBackground(
                    overlay,
                    "default/dark_temple",
                    ThemeCustomization.MODE_LIGHT,
                    null,
                )
            overlay.pairs.shouldBe(emptyMap())
        }

        "updatedColorSchemeBackground keeps unrelated overrides intact" {
            val first =
                ThemeCustomization.updatedColorSchemeBackground(
                    null,
                    "a/b",
                    ThemeCustomization.MODE_LIGHT,
                    "custom_a_b_day.png",
                )
            val second =
                ThemeCustomization.updatedColorSchemeBackground(
                    first,
                    "other/pair",
                    ThemeCustomization.MODE_DARK,
                    "custom_other_pair_night.png",
                )
            background(second, "a/b", ThemeCustomization.MODE_LIGHT) shouldBe "custom_a_b_day.png"
            background(second, "other/pair", ThemeCustomization.MODE_DARK) shouldBe "custom_other_pair_night.png"
        }

        "slotFileName sanitizes scheme ids and tags day/night" {
            ThemeCustomization.slotFileName("default/dark_temple", ThemeCustomization.MODE_DARK) shouldBe
                "custom_default_dark_temple_night.png"
            ThemeCustomization.slotFileName("google_white/google_black", ThemeCustomization.MODE_LIGHT) shouldBe
                "custom_google_white_google_black_day.png"
        }

        "slot alternation switches between two generations and back" {
            val scheme = "default/dark_temple"
            val dark = ThemeCustomization.MODE_DARK
            ThemeCustomization.secondSlotFileName(scheme, dark) shouldBe
                "custom_default_dark_temple_night_2.png"
            // No customization yet → first generation.
            ThemeCustomization.nextSlotFileName(scheme, dark, null) shouldBe
                "custom_default_dark_temple_night.png"
            // First generation applied → next apply moves to the alternate …
            ThemeCustomization.nextSlotFileName(scheme, dark, "custom_default_dark_temple_night.png") shouldBe
                "custom_default_dark_temple_night_2.png"
            // … and the apply after that returns to the first generation, so the
            // stored name changes on every apply (drives the theme refresh).
            ThemeCustomization.nextSlotFileName(scheme, dark, "custom_default_dark_temple_night_2.png") shouldBe
                "custom_default_dark_temple_night.png"
        }

        "slot file variants cover both generations for cleanup" {
            val light = ThemeCustomization.slotFileNameVariants("a/b", ThemeCustomization.MODE_LIGHT)
            light shouldBe listOf("custom_a_b_day.png", "custom_a_b_day_2.png")
            val dark = ThemeCustomization.slotFileNameVariants("a/b", ThemeCustomization.MODE_DARK)
            dark shouldBe listOf("custom_a_b_night.png", "custom_a_b_night_2.png")
        }
    })

private fun background(
    overlay: Node.Mapping?,
    schemeId: String,
    mode: String,
): String? {
    val schemes = overlay?.get(ThemeCustomization.COLOR_SCHEMES_KEY) as? Node.Mapping ?: return null
    val scheme = schemes[Node.Scalar(schemeId)] as? Node.Mapping ?: return null
    val palette = scheme[Node.Scalar(mode)] as? Node.Mapping ?: return null
    return (palette[Node.Scalar("keyboard_background")] as? Node.Scalar)?.string
}
