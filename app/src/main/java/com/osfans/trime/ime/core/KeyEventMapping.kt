/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.osfans.trime.core.KeyModifier
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.KeyValue
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.core.plus
import splitties.bitflags.hasFlag

/**
 * Android ↔ engine key-event conversions.
 *
 * These live in the UI layer (not `core/`) because they depend on
 * [KeyEvent]/[KeyCharacterMap]; the engine itself stays Android-free.
 */

fun KeyEvent.toKeyValue(): KeyValue {
    val charCode = unicodeChar
    // try charCode first, allow upper and lower case characters generating different KeyValue
    if (charCode != 0 &&
        // skip \t, because it's charCode is different from KeyValue
        charCode != '\t'.code &&
        // skip \n, because rime wants \r for return
        charCode != '\n'.code &&
        // skip Android's private-use character
        charCode != KeyCharacterMap.HEX_INPUT.code &&
        charCode != KeyCharacterMap.PICKER_DIALOG_INPUT.code
    ) {
        return KeyValue(charCode)
    }
    return KeyValue(RimeKeyMapping.keyCodeToVal(keyCode))
}

fun KeyEvent.toKeyModifiers(): KeyModifiers {
    val isRelease = action == KeyEvent.ACTION_UP
    // drop modifier state when using combination keys to input number/symbol on some phones
    // because rime doesn't recognize selection key with modifiers (eg. Alt+Q for 1)
    // in which case event.getNumber().toInt() == event.getUnicodeChar()
    // ... but some keys can have event.getNumber() return 0, need to check displayLabel as well
    val unicode = unicodeChar
    // skip ' ', because it would produce same unicodeChar regardless of the modifier
    if (unicode != 0 && unicode != ' '.code) {
        val char = unicode.toChar()
        if (char == number || displayLabel.uppercaseChar() != char.uppercaseChar()) {
            return if (isRelease) KeyModifiers.Release else KeyModifiers.Empty
        }
    }
    var states = KeyModifier.None.modifier
    apply {
        // we just need to make rime care about following uncommented
        // modifier states when forwarding physical keyboard event
        if (isAltPressed) states += KeyModifier.Alt
        if (isCtrlPressed) states += KeyModifier.Control
        if (isShiftPressed) states += KeyModifier.Shift
        if (isCapsLockOn) states += KeyModifier.Lock
//                if (isNumLockOn) states += KeyModifier.Mod2 // NumLock
//                if (isMetaPressed) states += KeyModifier.Meta
        if (isRelease) states += KeyModifier.Release
    }
    return KeyModifiers(states)
}

/** Android meta-state bits → engine [KeyModifiers]. */
fun Int.toKeyModifiers(): KeyModifiers {
    var states = KeyModifier.None.modifier
    if (hasFlag(KeyEvent.META_ALT_ON)) states += KeyModifier.Alt
    if (hasFlag(KeyEvent.META_CTRL_ON)) states += KeyModifier.Control
    if (hasFlag(KeyEvent.META_SHIFT_ON)) states += KeyModifier.Shift
    if (hasFlag(KeyEvent.META_NUM_LOCK_ON)) states += KeyModifier.Mod2 // NumLock
    if (hasFlag(KeyEvent.META_CAPS_LOCK_ON)) states += KeyModifier.Lock
    if (hasFlag(KeyEvent.META_META_ON)) states += KeyModifier.Meta
    return KeyModifiers(states)
}

/** Engine [KeyModifiers] → Android meta-state bits. */
fun KeyModifiers.toMetaState(): Int {
    var metaState = 0
    if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    if (meta) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    if (numLock) metaState = metaState or KeyEvent.META_NUM_LOCK_ON
    if (capsLock) metaState = metaState or KeyEvent.META_CAPS_LOCK_ON
    return metaState
}
