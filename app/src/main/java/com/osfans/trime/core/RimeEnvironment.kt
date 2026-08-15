/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

/**
 * Immutable environment snapshot the engine starts with.
 *
 * Keeps `Rime` free of Android storage/data-manager dependencies: the UI layer
 * resolves these (e.g. from [com.osfans.trime.data.base.DataManager]) and passes
 * them in.
 */
data class RimeEnvironment(
    val sharedDataDir: String,
    val userDataDir: String,
    val versionName: String,
)
