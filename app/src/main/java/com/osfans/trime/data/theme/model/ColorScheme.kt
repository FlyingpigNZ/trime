/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A self-contained color scheme pair.
 *
 * [colors] is the light/day palette; [darkColors] is the dark palette. A scheme
 * without an explicit dark palette uses [colors] as its dark fallback.
 */
@Parcelize
data class ColorScheme(
    val id: String,
    val colors: Map<String, String>,
    val darkColors: Map<String, String> = colors,
) : Parcelable {
    val displayName: String?
        get() = colors["name"] ?: darkColors["name"]
}
