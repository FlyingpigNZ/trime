// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

private const val SECTION_DIVIDER = ",.?!~:，。：～？！…\t\r\n\\/"

/**
 * Find the next (or previous) section boundary relative to [start].
 *
 * With [backward] = false, searches forward from [start] and returns the index
 * of the first section divider at or after it (the end of the current
 * section); with [backward] = true, searches the prefix and returns the last
 * divider before [start] (the start of the current section). Returns -1 when
 * no boundary exists in the searched direction.
 */
fun CharSequence.findSectionFrom(
    start: Int,
    backward: Boolean = false,
): Int {
    if (start !in 0..lastIndex) return -1
    return if (backward) {
        subSequence(0, start).indexOfLast { SECTION_DIVIDER.contains(it) }
    } else {
        val subSequence = subSequence(start, length)
        val relative = subSequence.indexOfFirst { SECTION_DIVIDER.contains(it) }
        if (relative < 0) -1 else start + relative
    }
}

fun CharSequence.splitWithSurrogates(): List<String> = buildList {
    var sur = Char(0)
    for (ch in this@splitWithSurrogates) {
        if (ch.isHighSurrogate()) {
            sur = ch
        } else if (ch.isLowSurrogate()) {
            add(String(charArrayOf(sur, ch)))
        } else {
            add(ch.toString())
        }
    }
}
