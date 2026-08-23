/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.res.Configuration
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.symbol.LiquidData
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping

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

    /**
     * Minimal built-in theme with no file dependencies. Applied as a last
     * resort when no package theme can be loaded, so the IME always starts
     * instead of crashing; the user can then fix the package in settings.
     */
    val fallbackTheme: Theme by lazy {
        Theme.decode(
            Yaml.Default.parseToYamlNode(FALLBACK_THEME).mapping
                ?: error("Builtin fallback theme is not a YAML mapping"),
        )
    }

    private const val FALLBACK_THEME =
        """
        name: fallback
        style:
          keyboard_height: 240
          candidate_view_height: 48
        preset_color_schemes:
          default:
            back_color: '0xff222222'
            text_color: '0xffe6e3d8'
            candidate_text_color: '0xffe6e3d8'
        """

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
