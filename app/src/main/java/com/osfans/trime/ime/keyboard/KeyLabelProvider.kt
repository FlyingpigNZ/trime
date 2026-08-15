/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.util.virtualKeyCharacterMap

/**
 * Supplies key labels and character mappings used while rendering key actions.
 *
 * The production implementation reads the Android virtual keyboard map; the
 * pure [AsciiKeyLabelProvider] is used by JVM unit tests (and is also a useful
 * fallback for the standard ASCII layout).
 */
interface KeyLabelProvider {
    fun isPrintingKey(code: Int): Boolean

    fun get(code: Int, metaState: Int): Int

    fun getDisplayLabel(code: Int, metaState: Int): String
}

object AndroidKeyLabelProvider : KeyLabelProvider {
    override fun isPrintingKey(code: Int): Boolean = virtualKeyCharacterMap.isPrintingKey(code)

    override fun get(code: Int, metaState: Int): Int = virtualKeyCharacterMap.get(code, metaState)

    override fun getDisplayLabel(code: Int, metaState: Int): String =
        KeyCode.getDisplayLabel(code, metaState)
}

/** Pure ASCII mapping used by unit tests and as a deterministic label source. */
object AsciiKeyLabelProvider : KeyLabelProvider {
    private val printableNames =
        mapOf(
            "space" to ' ',
            "numbersign" to '#',
            "apostrophe" to '\'',
            "asterisk" to '*',
            "plus" to '+',
            "comma" to ',',
            "minus" to '-',
            "period" to '.',
            "slash" to '/',
            "0" to '0',
            "1" to '1',
            "2" to '2',
            "3" to '3',
            "4" to '4',
            "5" to '5',
            "6" to '6',
            "7" to '7',
            "8" to '8',
            "9" to '9',
            "semicolon" to ';',
            "equal" to '=',
            "at" to '@',
            "bracketleft" to '[',
            "backslash" to '\\',
            "bracketright" to ']',
            "grave" to '`',
            "a" to 'a',
            "b" to 'b',
            "c" to 'c',
            "d" to 'd',
            "e" to 'e',
            "f" to 'f',
            "g" to 'g',
            "h" to 'h',
            "i" to 'i',
            "j" to 'j',
            "k" to 'k',
            "l" to 'l',
            "m" to 'm',
            "n" to 'n',
            "o" to 'o',
            "p" to 'p',
            "q" to 'q',
            "r" to 'r',
            "s" to 's',
            "t" to 't',
            "u" to 'u',
            "v" to 'v',
            "w" to 'w',
            "x" to 'x',
            "y" to 'y',
            "z" to 'z',
        )

    private val shiftedPrintable =
        mapOf(
            "1" to '!',
            "2" to '@',
            "3" to '#',
            "4" to '$',
            "5" to '%',
            "6" to '^',
            "7" to '&',
            "8" to '*',
            "9" to '(',
            "0" to ')',
            "minus" to '_',
            "equal" to '+',
            "bracketleft" to '{',
            "bracketright" to '}',
            "backslash" to '|',
            "semicolon" to ':',
            "apostrophe" to '"',
            "comma" to '<',
            "period" to '>',
            "slash" to '?',
            "grave" to '~',
            "space" to ' ',
        )

    override fun isPrintingKey(code: Int): Boolean =
        RimeKeyMapping.symbolCodeToLabel(code) != null ||
            RimeKeyMapping.keyCodeToName(code) in printableNames

    override fun get(
        code: Int,
        metaState: Int,
    ): Int {
        RimeKeyMapping.symbolCodeToLabel(code)?.firstOrNull()?.let { return it.code }
        val name = RimeKeyMapping.keyCodeToName(code) ?: return 0
        val shifted = metaState and (KeyEvent.META_SHIFT_ON or KeyEvent.META_CAPS_LOCK_ON) != 0
        val char =
            if (shifted) {
                shiftedPrintable[name] ?: printableNames[name]?.uppercaseChar()
            } else {
                printableNames[name]
            }
        return char?.code ?: 0
    }

    override fun getDisplayLabel(
        code: Int,
        metaState: Int,
    ): String {
        RimeKeyMapping.symbolCodeToLabel(code)?.let { return it }
        val name = RimeKeyMapping.keyCodeToName(code) ?: return ""
        return when {
            isPrintingKey(code) -> printableNames.getValue(name).toString()
            name.startsWith("KP_") -> name.removePrefix("KP_")
            else -> name.lowercase()
        }
    }
}
