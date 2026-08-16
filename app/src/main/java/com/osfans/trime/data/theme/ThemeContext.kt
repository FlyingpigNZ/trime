// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.KeyActionToken

/**
 * Single facade over the theme-related singletons.
 *
 * The long-term direction is to collapse [ThemeManager], [ColorManager],
 * [FontManager], and [KeyActionManager] behind this context so callers have one
 * access point and one change-notification flow.
 */
object ThemeContext {
    val activeTheme: Theme
        get() = ThemeManager.activeTheme

    val activeColorScheme: ColorScheme
        get() = ColorManager.activeColorScheme

    fun getColor(key: ThemeColor): Int = ColorManager.getColor(key)

    fun getColor(key: String): Int = ColorManager.getColor(key)

    fun getDrawable(key: ThemeColor): Drawable? = ColorManager.getDrawable(key)

    fun getDrawable(key: String): Drawable? = ColorManager.getDrawable(key)

    fun getTypeface(key: String): Typeface = FontManager.getTypeface(key)

    fun getAction(token: KeyActionToken) = KeyActionManager.getAction(token)

    fun getAction(token: String) = KeyActionManager.getAction(token)

    fun addOnThemeChangeListener(listener: ThemeManager.OnThemeChangeListener) {
        ThemeManager.addOnChangedListener(listener)
    }

    fun removeOnThemeChangeListener(listener: ThemeManager.OnThemeChangeListener) {
        ThemeManager.removeOnChangedListener(listener)
    }

    fun addOnColorChangeListener(listener: ColorManager.OnColorChangeListener) {
        ColorManager.addOnChangedListener(listener)
    }

    fun removeOnColorChangeListener(listener: ColorManager.OnColorChangeListener) {
        ColorManager.removeOnChangedListener(listener)
    }
}
