/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

data class RimeKeyEvent(
    val value: Int,
    val modifiers: Int,
    val repr: String,
) {
    override fun toString() = repr

    companion object {
        @JvmStatic
        external fun getKeycodeByName(name: String): Int
    }
}
