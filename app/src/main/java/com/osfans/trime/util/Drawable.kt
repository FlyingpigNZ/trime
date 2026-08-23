// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.annotation.ColorInt

fun rippleDrawable(
    @ColorInt color: Int,
) = RippleDrawable(ColorStateList.valueOf(color), null, ColorDrawable(Color.WHITE))

fun roundedRippleDrawable(
    @ColorInt color: Int,
    cornerRadius: Float,
    contentColor: Int = Color.TRANSPARENT,
): RippleDrawable {
    val contentDrawable = contentColor.takeUnless { it == Color.TRANSPARENT }?.let {
        GradientDrawable().apply {
            setColor(it)
            this.cornerRadius = cornerRadius
        }
    }
    val maskDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        this.cornerRadius = cornerRadius
    }
    return RippleDrawable(ColorStateList.valueOf(color), contentDrawable, maskDrawable)
}
