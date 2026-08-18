/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.res.Configuration
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.symbol.LiquidData
import com.osfans.trime.util.WeakHashSet

object ThemeManager {
    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    private lateinit var _activeTheme: Theme

    val isInitialized: Boolean
        get() = ::_activeTheme.isInitialized

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (::_activeTheme.isInitialized && _activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.defaultInstance().registerProvider(::ThemePrefs)

    fun init(configuration: Configuration) {
        check(::_activeTheme.isInitialized) { "No active IME package theme" }
        ColorManager.init(configuration)
    }

    /** Initialize the active theme if the IME view is created before Rime is ready. */
    fun ensureInitialized(configuration: Configuration) {
        if (!::_activeTheme.isInitialized) {
            init(configuration)
        }
    }

    /**
     * Apply an installed IME package. Self-contained packages replace the whole
     * Theme; there is no separate theme-selection concept anymore.
     */
    fun applySchemaLayout(
        layout: Theme,
        replaceTheme: Boolean = false,
    ) {
        val theme =
            if (replaceTheme) {
                layout
            } else {
                activeTheme.mergeSchemaLayout(layout)
            }
        KeyActionManager.resetCache()
        FontManager.resetCache(theme)
        ColorManager.switchTheme(theme)
        LiquidData.init(theme)
        activeTheme = theme
    }
}
