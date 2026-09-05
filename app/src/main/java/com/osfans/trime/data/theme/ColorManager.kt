// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.NinePatch
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.NinePatchDrawable
import androidx.annotation.ColorInt
import androidx.collection.LruCache
import androidx.core.graphics.drawable.toDrawable
import androidx.core.math.MathUtils
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.NinePatchBitmapFactory
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.isNightMode
import timber.log.Timber

object ColorManager {
    private lateinit var theme: Theme
    private val prefs = ThemeManager.prefs
    private var normalModeColor by prefs.normalModeColor
    private val followSystemDayNight by prefs.followSystemDayNight
    private val backgroundFolder get() = theme.generalStyle.backgroundFolder

    private var isNightMode = false

    private lateinit var _activeColorScheme: ColorScheme

    /**
     * Whether [init] has run. Distinct from _activeColorScheme being set:
     * switchTheme() also initializes the scheme (during package activation)
     * but does not know isNightMode yet — only init() derives it from the
     * system Configuration. ensureInitialized() must not skip init() just
     * because the scheme exists, or the keyboard resolves the day palette.
     */
    private var initDone = false

    /** Whether [init] has run (isNightMode is known). */
    val isInitialized: Boolean
        get() = initDone

    var activeColorScheme: ColorScheme
        get() = _activeColorScheme
        private set(value) {
            if (this::_activeColorScheme.isInitialized && _activeColorScheme == value) return
            _activeColorScheme = value
            rebuildResolvedPalette()
            fireChange()
        }

    /** Builtin fallback table merged with the theme's `fallback_colors`. */
    private var fallbackColors: Map<String, String> = emptyMap()

    /** Precomputed resolved color strings for the active palette. */
    private var resolvedPalette: Map<String, String> = emptyMap()

    private var bitmapCache: LruCache<String, Bitmap>? = null

    fun interface OnColorChangeListener {
        fun onColorChange(theme: Theme)
    }

    private val onChangeListeners = WeakHashSet<OnColorChangeListener>()

    fun addOnChangedListener(listener: OnColorChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnColorChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onColorChange(theme) }
    }

    private fun colorScheme(id: String) = theme.colorSchemes.find { it.id == id }

    fun init(configuration: Configuration) {
        isNightMode = configuration.isNightMode() && followSystemDayNight
        activeColorScheme = evaluateActiveColorScheme()
        initDone = true
        // activeColorScheme's setter skips rebuild when the scheme instance is
        // unchanged; init may be the first time isNightMode is known, so force
        // the palette rebuild to apply the correct day/night colors.
        rebuildResolvedPalette()

        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val cacheSize = maxMemory / 8
        bitmapCache =
            object : LruCache<String, Bitmap>(cacheSize.toInt()) {
                override fun sizeOf(
                    key: String,
                    value: Bitmap,
                ): Int = value.byteCount / 1024
            }
    }

    fun onSystemNightModeChange(isNight: Boolean) {
        isNightMode = isNight && followSystemDayNight
        // On a cold start the system can deliver the ui-mode configuration
        // change BEFORE ColorManager.init has run (Rime is still starting),
        // so _activeColorScheme is not initialized yet. Rebuilding the palette
        // then throws and the state is silently lost. Only rebuild when the
        // scheme is known; otherwise init() applies the recorded isNightMode.
        if (!this::_activeColorScheme.isInitialized) return
        rebuildResolvedPalette()
        fireChange()
    }

    private fun evaluateActiveColorScheme(): ColorScheme = colorScheme(normalModeColor)
        ?: colorScheme("default")
        ?: theme.colorSchemes.first()

    /** 每次切换主题后，都要调用此函数，初始化配色 */
    fun switchTheme(theme: Theme) {
        bitmapCache?.evictAll()
        this.theme = theme
        fallbackColors = BuiltinFallbackColors + theme.fallbackColors
        activeColorScheme = evaluateActiveColorScheme()
    }

    fun setColorScheme(scheme: ColorScheme) {
        activeColorScheme = scheme
        normalModeColor = scheme.id
    }

    /** The palette for the current day/night mode. */
    private val activePalette: Map<String, String>
        get() = if (isNightMode) activeColorScheme.darkColors else activeColorScheme.colors

    private fun rebuildResolvedPalette() {
        val keys = ThemeColor.entries.map { it.key }.toMutableSet()
        keys += activePalette.keys
        keys += fallbackColors.keys
        resolvedPalette =
            keys.mapNotNull { key ->
                try {
                    key to resolveValue(key) { it }
                } catch (_: Exception) {
                    null
                }
            }.toMap()
    }

    @ColorInt
    private fun resolveColor(key: String): Int {
        val color =
            try {
                val value = resolvedPalette[key] ?: resolveValue(key) { it }
                ColorUtils.parseColor(value)
            } catch (_: IllegalArgumentException) {
                ColorUtils.parseColor(key)
            }
        return color
    }

    private fun resolveDrawable(key: String): Drawable? {
        val drawable =
            try {
                val value = resolvedPalette[key] ?: resolveValue(key) { it }
                parseDrawable(value)
            } catch (_: IllegalArgumentException) {
                parseDrawable(key)
            }
        return drawable
    }

    private inline fun <T> resolveValue(
        key: String,
        parser: (String) -> T,
    ): T {
        var currentKey = key
        val visited = mutableSetOf<String>()

        while (true) {
            if (!visited.add(currentKey)) {
                throw IllegalArgumentException("Fallback color cycle detected at '$currentKey'")
            }
            val target = activePalette[currentKey]
            if (!target.isNullOrEmpty()) {
                Timber.d("current: $currentKey, origin: $key, target: $target")
                return parser(target)
            }
            val fallback = fallbackColors[currentKey]
            if (!fallback.isNullOrEmpty()) {
                currentKey = fallback
            } else {
                throw IllegalArgumentException("$key not found")
            }
        }
    }

    private fun parseDrawable(value: String): Drawable? {
        if (value.isEmpty()) return null
        if (SUPPORTED_IMG_FORMATS.any { value.endsWith(it) }) {
            val path = resolveImageFilePath(value)
            val bitmap =
                bitmapCache?.get(path)
                    ?: BitmapFactory.decodeFile(path)?.also {
                        bitmapCache?.put(path, it)
                    } ?: return null
            if (path.endsWith(".9.png")) {
                val chunk = bitmap.ninePatchChunk
                return if (NinePatch.isNinePatchChunk(chunk)) {
                    // for compiled nine patch image
                    NinePatchDrawable(Resources.getSystem(), bitmap, chunk, Rect(), null)
                } else {
                    // for source nine patch image
                    NinePatchBitmapFactory.createNinePatchDrawable(Resources.getSystem(), bitmap)
                }
            }
            return bitmap.toDrawable(Resources.getSystem())
        } else {
            val color =
                try {
                    ColorUtils.parseColor(value)
                } catch (_: Exception) {
                    Color.TRANSPARENT
                }
            return GradientDrawable().apply { setColor(color) }
        }
    }

    private fun resolveImageFilePath(value: String): String {
        val default = DataManager.userDataDir.resolve("backgrounds/$backgroundFolder/$value")
        if (!default.exists()) {
            val fallback = DataManager.userDataDir.resolve("backgrounds/$value")
            if (fallback.exists()) return fallback.absolutePath
        }
        return default.absolutePath
    }

    @ColorInt
    fun getColor(key: String): Int = resolveColor(key)

    fun getDrawable(key: String): Drawable? = resolveDrawable(key)

    @ColorInt
    fun getColor(key: ThemeColor): Int = getColor(key.key)

    fun getDrawable(key: ThemeColor): Drawable? = getDrawable(key.key)

    fun getDecorDrawable(
        colorKey: ThemeColor,
        borderColorKey: ThemeColor? = null,
        borderPx: Int = 0,
        cornerRadius: Float = 0f,
        alpha: Int = 255,
    ): Drawable? = getDecorDrawable(colorKey.key, borderColorKey?.key, borderPx, cornerRadius, alpha)

    fun getDecorDrawable(
        colorKey: String,
        borderColorKey: String? = null,
        borderPx: Int = 0,
        cornerRadius: Float = 0f,
        alpha: Int = 255,
    ): Drawable? = when (val drawable = getDrawable(colorKey)) {
        is GradientDrawable ->
            drawable.also {
                it.cornerRadius = cornerRadius
                it.alpha = MathUtils.clamp(alpha, 0, 255)
                if (!borderColorKey.isNullOrEmpty()) {
                    try {
                        val borderColor = getColor(borderColorKey)
                        it.setStroke(borderPx, borderColor)
                    } catch (_: Exception) {
                    }
                }
            }
        else -> drawable?.also { it.alpha = MathUtils.clamp(alpha, 0, 255) }
    }

    private val SUPPORTED_IMG_FORMATS = arrayOf(".png", ".webp", ".jpg", ".gif")
}
