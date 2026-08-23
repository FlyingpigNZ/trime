/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import splitties.bitflags.hasFlag

/**
 * translated from
 * [librime/key_table.h](https://github.com/rime/librime/blob/1.11.2/src/rime/key_table.h)
 */
enum class KeyModifier(
    val modifier: UInt,
) {
    None(0u),
    Shift(1u shl 0),
    Lock(1u shl 1), // CapsLock
    Control(1u shl 2),
    Mod1(1u shl 3),
    Alt(Mod1),
    Mod2(1u shl 4), // NumLock
    Meta(1u shl 28),
    Release(1u shl 30),
    ;

    constructor(other: KeyModifier) : this(other.modifier)
}

operator fun UInt.plus(other: KeyModifier) = or(other.modifier)

@JvmInline
value class KeyModifiers(
    val modifiers: UInt,
) {
    constructor(vararg modifiers: KeyModifier) : this(mergeModifiers(modifiers))

    fun has(modifier: KeyModifier) = modifiers.hasFlag(modifier.modifier)

    val alt get() = has(KeyModifier.Alt)
    val ctrl get() = has(KeyModifier.Control)
    val shift get() = has(KeyModifier.Shift)
    val meta get() = has(KeyModifier.Meta)
    val numLock get() = has(KeyModifier.Mod2) // NumLock
    val capsLock get() = has(KeyModifier.Lock)

    val release get() = has(KeyModifier.Release)

    fun toInt() = modifiers.toInt()

    companion object {
        val Empty = KeyModifiers(0u)

        val Release = KeyModifiers(KeyModifier.Release)

        fun of(v: Int) = KeyModifiers(v.toUInt())

        fun mergeModifiers(arr: Array<out KeyModifier>): UInt = arr.fold(KeyModifier.None.modifier) { acc, it -> acc or it.modifier }
    }
}
