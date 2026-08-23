// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import androidx.annotation.ColorInt
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.toColorInt

object ColorUtils {
    /**
     * Parse a color string into an Android [ColorInt] (`0xAARRGGBB`, alpha in
     * the high byte — Android's native order).
     *
     * Accepted forms (same semantics as [android.graphics.Color.parseColor]):
     * - `0xAARRGGBB` / `#AARRGGBB` — 8 hex digits, **alpha first**;
     * - `0xRRGGBB` / `#RRGGBB` — 6 hex digits (opaque);
     * - shorter forms, and named colors (`red`, `blue`, …).
     *
     * Theme authors must write 8-digit colors as `0xAARRGGBB`. A value like
     * `0x80141617` is black at 50% alpha; writing the alpha last
     * (`0x14161780`, CSS style) would be parsed as a different color.
     */
    @ColorInt
    fun parseColor(colorString: String): Int {
        val normalized =
            if (colorString.startsWith("#") || colorString.startsWith("0x", ignoreCase = true)) {
                val sub = colorString.replace("^#|^0x".toRegex(), "")
                when (sub.length) {
                    1, 2 -> "#%02x000000".format(java.lang.Long.decode(colorString)) // 0xA(A) -> #AA000000
                    in 3..5 -> "#%06x".format(java.lang.Long.decode(colorString)) // 0xGBB... -> #RRGGBB
                    7 -> "#0$sub"
                    else -> "#$sub" // 0x(AA)RRGGBB -> #(AA)RRGGBB
                }
            } else {
                colorString // red, green, blue ...
            }
        return normalized.toColorInt()
    }

    /**
     * 计算颜色相对亮度，如果超出 0.73，认为是亮色，否则认为是暗色
     */
    fun isContrastedDark(
        @ColorInt color: Int,
    ): Boolean {
        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f
        return (r * 0.2126f + g * 0.7152f + b * 0.0722f) <= 0.73f
    }
}
