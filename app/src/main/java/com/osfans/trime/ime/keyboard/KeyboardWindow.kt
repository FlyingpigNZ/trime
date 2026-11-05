// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.Point
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.disambiguation.PinyinDisambiguationController
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.DiagnosticLog
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.windowManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

/**
 * Renders the current keyboard and delegates all selection policy (target
 * resolution, schema smart-match, ascii-mode sync) to [KeyboardSwitcher].
 */
class KeyboardWindow :
    BoardWindow.NoBarBoardWindow(),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by di.instance()
    private val theme: Theme by di.instance()
    private val rime: RimeSession by di.instance()
    private val switcher: KeyboardSwitcher by di.instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val popup: PopupDelegate by di.instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by di.instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    companion object : ResidentWindow.Key {
        private const val TAG = "keyboard"

        /**
         * Tolerance (dp) of [resolveWindowMetricsWidth]: a window-metrics width
         * narrower than the display by more than this is treated as having
         * stale insets rather than being believed.
         */
        private const val INSET_TOLERANCE_DP = 1
    }

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val cachedKeyboardViews = mutableMapOf<String, KeyboardView>()

    /**
     * Width (px) of the container the keyboard is drawn into, as measured by
     * the last layout pass, together with the orientation it was measured in —
     * see [resolveKeyboardWidth]. Both are reset on a configuration change,
     * because a width measured in the other orientation describes the previous
     * layout.
     */
    private var measuredContainerWidth = 0
    private var measuredInLandscape: Boolean? = null

    /** The device orientation, independent of the landscape-mode preference. */
    private val isLandscapeOrientation: Boolean
        get() = context.resources.configuration.isLandscape()

    /**
     * Tracks the container's real width. The keys are drawn into this view, so
     * its width is the authoritative layout width: re-deriving it on every
     * change is what keeps a width read while the window was still settling
     * (e.g. right after boot) from sticking to the layout forever.
     */
    private val onContainerLayoutChange =
        View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            val landscape = isLandscapeOrientation
            if (width <= 0 || (width == measuredContainerWidth && landscape == measuredInLandscape)) {
                return@OnLayoutChangeListener
            }
            measuredContainerWidth = width
            measuredInLandscape = landscape
            // Rebuilding the view tree from inside a layout pass would recurse.
            v.post { rebuildKeyboards() }
        }

    private val currentKeyboard: Keyboard? get() = switcher.currentKeyboard
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboardViews[switcher.currentKeyboardId]

    private val keyboardActionListener = commonKeyboardActionListener.listener

    /**
     * Pinyin disambiguation (feature ②): a floating strip over the active
     * keyboard — the T9 keyboards' first punctuation column, or the
     * 小鹤双拼14键 keyboards' first digit row — listing every legal pinyin
     * parse of the current key code. Exposed (internal) so the key listener
     * can route T9 digit keys, 14-key letter keys and Backspace to it before
     * they reach Rime.
     */
    val pinyinDisambiguation = PinyinDisambiguationController(rime, theme)

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardView.addOnLayoutChangeListener(onContainerLayoutChange)
        applyKeyboardWidth()
        attachKeyboard(switcher.resolveKeyboard(".default"))
        return keyboardView
    }

    private fun detachCurrentView() {
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
        switcher.detachCurrentKeyboard()
    }

    /**
     * Rebuild the current keyboard after a configuration change (e.g.
     * rotation). Both the [Keyboard] model and the [KeyboardView] caches are
     * orientation-dependent and must be recreated with fresh metrics; the
     * target is re-resolved so landscape variants are picked up both ways.
     */
    fun onConfigurationChanged() {
        // The measurement describes the previous orientation.
        measuredContainerWidth = 0
        measuredInLandscape = null
        rebuildKeyboards()
    }

    /**
     * Rebuild every keyboard for the current layout width: cached [Keyboard]
     * models have the width they were built with baked into their key
     * geometry, and cached [KeyboardView]s their key bounds, so both caches
     * must be dropped together. The target is re-resolved so a changed
     * orientation also re-picks the landscape variants.
     */
    private fun rebuildKeyboards() {
        detachCurrentView()
        cachedKeyboardViews.clear()
        switcher.invalidateKeyboardCache()
        applyKeyboardWidth()
        attachKeyboard(switcher.currentKeyboardId.ifEmpty { ".default" })
    }

    /**
     * Resolve the width the keys are laid out on and hand it to the switcher,
     * which drops the cached keyboards when it changed. The choice is logged:
     * a container width and an estimate that disagree is exactly the condition
     * that draws every key wider than the surface it is drawn on.
     */
    private fun applyKeyboardWidth() {
        val landscape = isLandscapeOrientation
        val estimate = estimateKeyboardWidth()
        val width = resolveKeyboardWidth(measuredContainerWidth, measuredInLandscape, landscape, estimate)
        if (switcher.updateAllowedWidth(width)) {
            val source = if (measuredContainerWidth > 0 && measuredInLandscape == landscape) "container" else "estimate"
            DiagnosticLog.i(
                TAG,
                "layout width=${width}px source=$source measured=${measuredContainerWidth}px " +
                    "estimate=${estimate}px display=${context.resources.displayMetrics.widthPixels}px landscape=$landscape",
            )
        }
    }

    /**
     * Width estimate from the windowing system, used only until the container
     * has been laid out once (this is what [Keyboard] used to read directly).
     * See [resolveWindowMetricsWidth] for the stale-inset guard.
     */
    private fun estimateKeyboardWidth(): Int {
        val padding = theme.generalStyle.run {
            if (context.isLandscapeMode()) keyboardPaddingLand else keyboardPadding
        }
        val safeWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = context.windowManager.maximumWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            resolveWindowMetricsWidth(
                windowWidth = windowMetrics.bounds.width() - insets.left - insets.right,
                displayWidth = context.resources.displayMetrics.widthPixels,
                tolerancePx = context.dp(INSET_TOLERANCE_DP),
            )
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            context.windowManager.defaultDisplay.getSize(size)
            size.x
        }
        return safeWidth - 2 * context.dp(padding)
    }

    private fun attachKeyboard(target: String) {
        val keyboard = switcher.selectKeyboard(target)
        val keyboardId = switcher.currentKeyboardId
        val view =
            cachedKeyboardViews.getOrPut(keyboardId) {
                KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay, rime)
            }

        keyboard.also {
            runBlocking { _currentKeyboardHeight.emit(it.keyboardHeight) }
            dispatchCapsState(it::setShifted)
        }

        view.let {
            keyboardView.apply {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                add(it, lParams(matchParent, matchParent))
            }
        }

        // Attach the disambiguation panel on top of the keyboard view so it
        // covers the strip the active extension targets (the first punctuation
        // column of a T9 keyboard, or the first digit row of a 14-key
        // keyboard). The current keyboard id is passed so the controller can
        // gate the overlay on the keyboard id the schema declares
        // (`t9_disambiguation.keyboard`).
        pinyinDisambiguation.attach(keyboardView, keyboard, keyboardId)
    }

    fun switchKeyboard(
        to: String,
        onSwitched: (() -> Unit)? = null,
    ) {
        val target = switcher.resolveKeyboard(to)
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboardViews.containsKey(target)) {
                if (target == switcher.currentKeyboardId) {
                    // Target is already current; the post-switch callback must
                    // still run (e.g. the start-input ascii policy).
                    onSwitched?.invoke()
                    return@execute
                }
            }
            detachCurrentView()
            attachKeyboard(target)
            onSwitched?.invoke()
        }
        Timber.d("Switched to keyboard: $target")
    }

    override fun onStartInput(info: EditorInfo) {
        val target = switcher.startInputTarget(info)
        // Apply the ascii policy after the deferred switch actually happened;
        // applying it before would run it against the OLD keyboard (K-M1).
        switchKeyboard(target) { switcher.applyStartInputPolicy(target) }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.uiState.value.status
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.generalStyle.autoCaps && status.isAsciiMode && currentKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.uiState.value.isAsciiMode) {
            currentKeyboard?.appearanceStateKeys?.forEach { key ->
                currentKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            currentKeyboard?.setShifted(on, shifted)?.let { if (it) currentKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        // Apply the schema-level toolbar override (feature ①) before
        // rebuilding the keyboard so the toolbar follows the current schema.
        ThemeManager.applySchemaToolBar(schema.id)
        pinyinDisambiguation.onRimeSchemaUpdated(schema)
        switchKeyboard(".default")
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        pinyinDisambiguation.onCompositionUpdate(data)
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        val option = value.option
        when {
            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }
            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(KeyActionManager.getAction(what))
                }
            }

            option == "ascii_mode" && !value.value -> {
                // 回到中文时，释放 Shift 锁定
                currentKeyboard?.resetShift()
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onEnterKeyLabelUpdate(label: String) {
        cachedKeyboardViews.values.forEach { it.invalidateAllKeys() }
    }

    override fun onAttached() {
        // The window's view is cached and reused across attach/detach cycles
        // (e.g. the unrolled candidate window replaces the keyboard window, and
        // onDetached() tears down the disambiguation panel). Re-attach it when
        // the window comes back, otherwise the panel never reappears until the
        // next schema switch re-runs attachKeyboard via switchKeyboard.
        val keyboard = currentKeyboard ?: return
        if (!pinyinDisambiguation.isAttached) {
            pinyinDisambiguation.attach(keyboardView, keyboard, switcher.currentKeyboardId)
        }
    }

    override fun onDetached() {
        currentKeyboardView?.onDetach()
        pinyinDisambiguation.detach()
    }
}
