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

    /**
     * The package-level `tool_bar` (chrome.yaml), before any per-schema
     * `<schemaId>.extended.yaml` override is applied. Kept separate so
     * switching to a schema without an override restores the base toolbar
     * instead of stacking stale overrides.
     */
    private var baseToolBar: com.osfans.trime.data.theme.model.ToolBar =
        com.osfans.trime.data.theme.model.ToolBar()

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

    /**
     * Initialize the active theme if the IME view is created before Rime is
     * ready. Checks both the theme and the ColorManager: the theme
     * (_activeTheme) can be set early (e.g. applySchemaLayout on package
     * activation) while ColorManager.init has not run yet, so isNightMode is
     * still unknown and the keyboard would resolve the day palette (wrong
     * background) instead of the night one.
     */
    fun ensureInitialized(configuration: Configuration) {
        if (!::_activeTheme.isInitialized || !ColorManager.isInitialized) {
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
        baseToolBar = theme.toolBar
        KeyActionManager.resetCache()
        FontManager.resetCache(theme)
        ColorManager.switchTheme(theme)
        LiquidData.init(theme)
        activeTheme = theme
    }

    /**
     * Apply the active schema's `<schemaId>.extended.yaml` `tool_bar` override
     * (feature ①) onto the current theme. When [schemaId] has no override the
     * base package toolbar is restored, so switching schemas never leaves a
     * stale toolbar behind. Returns true when the toolbar actually changed.
     *
     * The theme rebuild re-runs the theme-change listener, which recreates the
     * input view and the whole DI graph — the centralized way every toolbar
     * read point (AlwaysUi/ButtonsBarUi/TabUi/SegmentsWindow/FontManager)
     * observes the new toolbar.
     */
    fun applySchemaToolBar(schemaId: String): Boolean {
        // Unlike applySchemaLayout we deliberately do NOT reset the
        // KeyActionManager cache here: a toolbar only re-wires which action
        // names a button refers to; it does not change the preset_keys /
        // preset_keyboards that define how a name resolves to a KeyAction. The
        // action cache is keyed by name and resolves lazily, so a newly
        // referenced action is parsed on first use. applySchemaLayout resets it
        // because it replaces the key layouts themselves.
        val ext = SchemaExtensionResolver.loadForSchema(schemaId)
        val newToolBar = when {
            ext == null -> baseToolBar
            ext.toolBar?.replace == true -> ext.toolBar!!
            ext.toolBarNode != null -> SchemaExtensionResolver.mergeToolBarNode(baseToolBar, ext.toolBarNode)
            else -> baseToolBar
        }
        if (newToolBar == activeTheme.toolBar) return false
        val merged = activeTheme.copy(toolBar = newToolBar)
        FontManager.resetCache(merged)
        activeTheme = merged
        return true
    }
}
