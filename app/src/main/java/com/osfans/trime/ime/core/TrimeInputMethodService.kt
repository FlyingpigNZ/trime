/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.schema.PackageCompiler
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.util.DiagnosticLog
import com.osfans.trime.util.any
import com.osfans.trime.util.forceShowSelf
import com.osfans.trime.util.monitorCursorAnchor
import com.osfans.trime.util.styledFloat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.systemservices.inputMethodManager
import timber.log.Timber

/** [輸入法][InputMethodService]主程序  */

open class TrimeInputMethodService : LifecycleInputMethodService() {
    private lateinit var rime: RimeSession
    private val jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    /** Text editing / editor-action logic, split out of this god class. */
    val editor = ImeEditor(this)

    /** Engine message routing, split out of this god class. */
    private val messageDispatcher = RimeMessageDispatcher(this)

    // Bridges to InputMethodService protected members for [ImeEditor].
    internal val inputConnection get() = currentInputConnection
    internal val inputEditorInfo get() = currentInputEditorInfo

    internal fun sendEnterKeyDownUp() = sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    internal val rimeSession: RimeSession
        get() = rime

    private val prefs = AppPrefs.defaultInstance()
    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private lateinit var lastKnownConfig: Configuration

    /**
     * Whether the IME window is currently requested to be shown.
     *
     * Rebuilding the input view (theme/color/preference changes) while the
     * window is hidden leaves a freshly built view tree inside a window that
     * keeps its previous size until the next show, and a keyboard model built
     * in the new configuration (density!) then paints at the wrong height
     * inside that stale window — the "shrunken keyboard over the real one"
     * report.
     * Rebuilds requested while hidden are deferred to [onWindowShown].
     */
    private var windowShown = false
    private var pendingInputViewRebuild: (() -> Unit)? = null
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null
    private val navBarManager = NavigationBarManager()
    private val inputDeviceManager = InputDeviceManager { useVirtualKeyboard, useCandidatesView ->
        postRimeJob {
            setCandidatePagingMode(useCandidatesView)
        }
        currentInputConnection?.monitorCursorAnchor(useCandidatesView)
        window.window?.let {
            navBarManager.evaluate(it, useVirtualKeyboard)
        }
    }
    private val rimeIntentReceiver = RimeIntentReceiver()

    private var cursorUpdateIndex = 0

    private val recreateInputViewPrefs: Array<PreferenceDelegate<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        prefs.keyboard.hideInputBar,
        prefs.keyboard.heightScale,
        prefs.advanced.ignoreSystemGestureInsets,
    )

    @Keep
    private val recreateInputViewListener =
        PreferenceDelegate.OnChangeListener<Any> { _, _ ->
            whenWindowShown { replaceInputView(ThemeManager.activeTheme) }
        }

    @Keep
    private val recreateCandidatesViewListener =
        PreferenceDelegateProvider.OnChangeListener {
            replaceCandidateView(ThemeManager.activeTheme)
        }

    @Keep
    private val onThemeChangeListener =
        ThemeManager.OnThemeChangeListener {
            DiagnosticLog.i("ime", "theme changed -> replace input views")
            whenWindowShown { replaceInputViews(it) }
        }

    @Keep
    private val onColorChangeListener =
        ColorManager.OnColorChangeListener {
            ContextCompat.getMainExecutor(this).execute {
                whenWindowShown { restyleInputViews(it) }
            }
        }

    private fun postJob(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a rime operation to [jobs] to be executed
     *
     * Unlike `rime.runOnReady` or `rime.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postRimeJob] ensures that operations are executed sequentially.
     */
    fun postRimeJob(block: suspend RimeApi.() -> Unit) = postJob(rime.lifecycleScope) { rime.runOnReady(block) }

    private suspend fun updateRimeOption(api: RimeApi) {
        try {
            api.setRuntimeOption("soft_cursor", prefs.keyboard.useSoftCursor.getValue()) // 軟光標
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun registerReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreate() {
        DiagnosticLog.i("ime", "service onCreate")
        rime = RimeDaemon.createSession(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            rime.messageFlow.collect { message ->
                // Never let one malformed message kill the collector: that
                // would silently stop commits/preedit/candidates/key handling
                // for the whole service lifetime.
                runCatching { messageDispatcher.handle(message) }
                    .onFailure { t -> Timber.e(t, "Failed to handle Rime message: $message") }
            }
        }
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        // ensure theme and color managers are initialized after rime is ready.
        // runOnReadyOrFailed: a failed initial deploy must still run the
        // package/theme fallback path instead of suspending forever.
        lifecycleScope.launch {
            rime.runOnReadyOrFailed {
                // A corrupt/undeployable Default workspace must not crash the
                // IME at startup: log, apply the builtin fallback theme, and
                // let the rest of the bootstrap continue. Catch Exception
                // (not Throwable) so genuine Errors still surface.
                try {
                    ImePackageManager.ensureDefaultPackageReady()
                } catch (t: Exception) {
                    if (t is CancellationException) throw t
                    Timber.e(t, "Default package bootstrap failed; applying fallback theme")
                    runCatching { PackageCompiler.applyTheme(ThemeManager.fallbackTheme) }
                        .onFailure { e -> Timber.e(e, "Fallback theme unavailable") }
                }
                ThemeManager.init(resources.configuration)
                ThemeManager.addOnChangedListener(onThemeChangeListener)
                ColorManager.addOnChangedListener(onColorChangeListener)
            }
        }
        InputFeedbackManager.init(this)
        registerReceiver()
        super.onCreate()
        Timber.d("onCreate")
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = Configuration(resources.configuration)
    }

    private fun replaceInputView(theme: Theme): InputView {
        val newInputView = InputView(this, rime, theme)
        setInputView(newInputView)
        inputDeviceManager.setInputView(newInputView)
        inputView = newInputView
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, rime, theme)
        contentView.removeView(candidatesView)
        contentView.addView(newCandidatesView)
        inputDeviceManager.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        if (decorLocationUpdated) {
            candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
        } else {
            workaroundNullCursorAnchorInfo()
        }
        return newCandidatesView
    }

    private fun replaceInputViews(theme: Theme) {
        navBarManager.evaluate(window.window!!, inputDeviceManager.useVirtualKeyboard)
        replaceInputView(theme)
        replaceCandidateView(theme)
        currentInputEditorInfo?.let { editorInfo ->
            inputView?.updateEnterKeyLabel(editorInfo)
        }
    }

    /**
     * Color changes rebuild the input/candidate views. Rebuilding re-creates the
     * DI graph and all delegates/views, which is the only reliable way to clear
     * the many color/drawable caches scattered across the IME UI.
     */
    private fun restyleInputViews(theme: Theme) {
        DiagnosticLog.i("ime", "color changed -> replace input views")
        replaceInputViews(theme)
    }

    /**
     * Run [rebuild] now when the IME window is up, or as soon as it is shown
     * again. See [windowShown] for why rebuilding while hidden is not safe.
     */
    private fun whenWindowShown(rebuild: () -> Unit) {
        if (windowShown) {
            rebuild()
        } else {
            DiagnosticLog.i("ime", "deferred input view rebuild (window hidden)")
            pendingInputViewRebuild = rebuild
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        windowShown = true
        applyImeWindowHeight()
        pendingInputViewRebuild?.let { rebuild ->
            pendingInputViewRebuild = null
            DiagnosticLog.i("ime", "applying deferred input view rebuild")
            rebuild()
        }
        DiagnosticLog.i("ime", "window shown ${windowGeometry()} ${inputView?.geometry()}")
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        windowShown = false
        DiagnosticLog.i("ime", "window hidden ${windowGeometry()} ${inputView?.geometry()}")
    }

    /**
     * Window-level snapshot for the persistent log, printed next to the view
     * geometry on every show/hide.
     *
     * The window identity answers a question the view geometry cannot: whether
     * the framework *reused* the IME window or built a new one. A fresh id means
     * a new surface, which is the one situation where a stale frame and a new
     * (possibly differently laid out) frame can be composited at the same time —
     * the "complete but shrunken keyboard over the real one" shape.
     * `params` and `anim` cover the compositor side of the same question: the
     * window animation style our theme hands to the OEM's show/hide transition
     * is what a scale/fade on the whole window would come from.
     */
    private fun windowGeometry(): String {
        val win = runCatching { window?.window }.getOrNull()
        val attrs = win?.attributes
        val decor =
            if (this::decorView.isInitialized) {
                "${decorView.width}x${decorView.height} attached=${decorView.isAttachedToWindow}"
            } else {
                "uninitialized"
            }
        return "win=${win?.let { System.identityHashCode(it) } ?: 0} decor=$decor " +
            "params=${attrs?.width}x${attrs?.height} gravity=${attrs?.gravity} anim=${attrs?.windowAnimations}"
    }

    override fun onDestroy() {
        DiagnosticLog.i("ime", "service onDestroy")
        InputFeedbackManager.destroy()
        inputView = null
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        ColorManager.removeOnChangedListener(onColorChangeListener)
        super.onDestroy()
        unregisterReceiver(rimeIntentReceiver)
        RimeDaemon.destroySession(javaClass.name)
    }

    /**
     * https://github.com/fcitx5-android/fcitx5-android/blob/fe3a618c8fd18842305d2f8ec2880fcc67ec1679/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L523-#L547
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        postRimeJob { clearComposition() }
        val keyboardUiModeMask = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        DiagnosticLog.i("ime", "onConfigurationChanged diff=$diff orientation=${newConfig.orientation}")
        if (diff and keyboardUiModeMask != diff) {
            super.onConfigurationChanged(newConfig)
        }
        // The IME window survives rotation *and* display-mode ("smart
        // resolution") switches. The keyboard models bake the configuration
        // they were built in into their geometry — row heights, gaps and label
        // sizes go through the density and font scale, and the width comes from
        // the container measured for that configuration — so every one of these
        // changes must rebuild them, not just rotation.
        val keyboardMetricsMask =
            ActivityInfo.CONFIG_ORIENTATION or
                ActivityInfo.CONFIG_DENSITY or
                ActivityInfo.CONFIG_FONT_SCALE
        if (diff and keyboardMetricsMask != 0) {
            inputView?.onKeyboardMetricsChanged()
        }
        lastKnownConfig.setTo(newConfig)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] =
            if (inputDeviceManager.useVirtualKeyboard) {
                inputViewLocation[1].toFloat()
            } else {
                contentView.height.toFloat()
            }
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = RectF()

    private fun workaroundNullCursorAnchorInfo() {
        anchorPosition.set(0f, contentSize[1], 0f, contentSize[1])
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        // The caret side depends on the composing text's own direction, not
        // the IME window's layout direction (which follows the device
        // locale); for RTL text the caret sits at the right edge of the
        // first character. Per-character RTL flags exist since API 30; fall
        // back to the window direction on older platforms or when no
        // character is available.
        val isRtl =
            if (bounds != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    // FLAG_IS_RTL is per-character; a neutral leading
                    // character (digit/punctuation) carries no flag, so fall
                    // back to the window direction in that case too.
                    if ((info.getCharacterBoundsFlags(0) and CursorAnchorInfo.FLAG_IS_RTL) != 0) {
                        true
                    } else {
                        candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    }
                }.getOrDefault(candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            } else {
                candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL
            }
        // update anchorPosition
        if (bounds == null) {
            // composing is disabled in target app or trime settings
            // use the position of the insertion marker instead
            anchorPosition.top = info.insertionMarkerTop
            anchorPosition.left = info.insertionMarkerHorizontal
            anchorPosition.bottom = info.insertionMarkerBottom
            anchorPosition.right = info.insertionMarkerHorizontal
        } else {
            // for different writing system (e.g. right to left languages),
            // we have to calculate the correct RectF
            val horizontal = if (isRtl) bounds.right else bounds.left
            anchorPosition.top = bounds.top
            anchorPosition.left = horizontal
            anchorPosition.bottom = bounds.bottom
            anchorPosition.right = horizontal
        }
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            workaroundNullCursorAnchorInfo()
            return
        }
        info.matrix.mapRect(anchorPosition)
        val (dX, dY) = decorLocation
        anchorPosition.offset(-dX, -dY)
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize, isRtl)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        cursorUpdateIndex += 1
        handleCursorUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd, cursorUpdateIndex)
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        updateIndex: Int,
    ) {
        if (newSelStart != newSelEnd) return
        if (candidatesStart == candidatesEnd) return
        if (newSelStart in candidatesStart..candidatesEnd) {
            val position = newSelStart - candidatesStart
            if (position != editor.composingText.length) {
                postRimeJob {
                    if (updateIndex != cursorUpdateIndex) return@postRimeJob
                    Timber.d("handleCursorUpdate: move rime cursor to $position")
                    moveCursorPos(position)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: clear composition")
            postRimeJob {
                clearComposition()
            }
        }
    }

    private val inputViewLocation = intArrayOf(0, 0)

    /** Last insets/hit-test record written to the persistent log. */
    private var lastInsetsRecord: String? = null

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceManager.useVirtualKeyboard) {
            inputView?.keyboardView?.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
        // The insets are how the editor learns where our UI sits. A change with
        // no configuration change behind it means the window was laid out again
        // (screen on/off, IME window rebuild); the keyboard's own top edge is
        // the number a "shrunken keyboard" would move.
        val record =
            "content=${outInsets.contentTopInsets} visible=${outInsets.visibleTopInsets} " +
                "keyboardTop=${inputViewLocation[1]} " +
                "decor=${if (this::decorView.isInitialized) "${decorView.width}x${decorView.height}" else "?"}"
        if (record != lastInsetsRecord) {
            lastInsetsRecord = record
            DiagnosticLog.i("ime", "insets $record")
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onCreateInputView(): View? {
        Timber.d("onCreateInputView")
        // The framework asks for a new input view exactly when it needs content
        // for a (re)built IME window. That is the one event that explains a
        // stale surface standing next to a fresh one, so it goes to the
        // persistent log along with the window it is being built into.
        DiagnosticLog.i("ime", "create input view ${windowGeometry()} themeReady=${ThemeManager.isInitialized}")
        // If Rime is not ready yet and no package has been activated, return a
        // lightweight placeholder instead of blocking the IME main thread on
        // package activation. The background activation replaces it with the
        // real input view as soon as the theme is available.
        if (!ThemeManager.isInitialized) {
            val placeholder = FrameLayout(this)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    // A failed Default bootstrap must not crash the IME nor
                    // leave the placeholder forever; fall back and continue.
                    // Catch Exception (not Throwable) so Errors still surface.
                    try {
                        ImePackageManager.ensureDefaultPackageReady()
                    } catch (t: Exception) {
                        if (t is CancellationException) throw t
                        Timber.e(t, "Default package bootstrap failed; applying fallback theme")
                        runCatching { PackageCompiler.applyTheme(ThemeManager.fallbackTheme) }
                            .onFailure { e -> Timber.e(e, "Fallback theme unavailable") }
                    }
                }
                ThemeManager.ensureInitialized(resources.configuration)
                replaceInputViews(ThemeManager.activeTheme)
            }
            return placeholder
        }
        ThemeManager.ensureInitialized(resources.configuration)
        replaceInputViews(ThemeManager.activeTheme)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        DiagnosticLog.i(
            "ime",
            "set input view view=${System.identityHashCode(view)} ${windowGeometry()}",
        )
        val inputArea = contentView.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        // AOSP's semantics, with one difference: instead of MATCH_PARENT the IME
        // window is only as tall as our keyboard plus the room our popups and the
        // preedit strip need above it.
        //
        // A full-screen window means the compositor keeps blending a
        // display-sized layer at 120Hz whenever the keyboard is up. On Honor's
        // MagicOS that is enough for the platform to classify the app as a game
        // and render *the whole process* at a reduced resolution upscaled to the
        // panel (see doc/handoff.md §13: `apsResolutionRatio = 0.8493`, thawed for
        // one frame at screen-off as a "shrunken keyboard"). Making the window
        // describe what it actually paints keeps the surface small and stops
        // looking like a full-screen game.
        val compactHeight = inputView?.requestedWindowHeight() ?: 0
        val height =
            when {
                isFullscreen -> ViewGroup.LayoutParams.MATCH_PARENT
                isCandidatesOnly -> ViewGroup.LayoutParams.WRAP_CONTENT
                compactHeight > 0 -> fitWindowHeight(compactHeight)
                // Nothing measured yet (first show): keep the window out of the
                // way until the keyboard has a height we can size it to.
                else -> ViewGroup.LayoutParams.MATCH_PARENT
            }
        appliedWindowHeight = height.takeIf { it > 0 } ?: 0
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        DiagnosticLog.i(
            "ime",
            "configure window fullscreen=$isFullscreen candidatesOnly=$isCandidatesOnly " +
                "layout=${win.attributes.width}x${win.attributes.height} anim=${win.attributes.windowAnimations}",
        )
    }

    /**
     * Re-size the IME window to [InputView.requestedWindowHeight] after the
     * keyboard changed (theme, layout, orientation, density). The window must
     * never be shorter than the keyboard, or the keys would be clipped in a
     * window that cannot grow, and never taller than needed, or we are back to a
     * display-sized surface.
     */
    fun applyImeWindowHeight() {
        if (isFullscreenMode() || !isInputViewShown) return
        val wanted = inputView?.requestedWindowHeight()?.takeIf { it > 0 } ?: return
        val height = fitWindowHeight(wanted)
        if (height == appliedWindowHeight) return
        appliedWindowHeight = height
        window.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        DiagnosticLog.i("ime", "ime window height=$height ${inputView?.geometry()}")
    }

    /**
     * [wanted] when the window may actually be that tall, otherwise `MATCH_PARENT`.
     *
     * Requesting more than the window can get is not harmless: the window manager
     * clamps the *frame* but the view tree keeps the requested size, so everything
     * anchored to the bottom (the keyboard) ends up below the visible area. A
     * landscape keyboard plus its headroom does not fit, which is why AOSP runs a
     * full-screen window there as well.
     */
    private fun fitWindowHeight(wanted: Int): Int {
        val available = availableWindowHeight()
        return if (available <= 0 || wanted < available) wanted else ViewGroup.LayoutParams.MATCH_PARENT
    }

    /** Display height the IME window may use: the screen minus the status bar/cutout. */
    private fun availableWindowHeight(): Int {
        if (!this::decorView.isInitialized) return 0
        val insets = ViewCompat.getRootWindowInsets(decorView) ?: return 0
        val top =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
        return resources.displayMetrics.heightPixels - top
    }

    /** Window height we last asked for; 0 means MATCH_PARENT (nothing applied yet). */
    private var appliedWindowHeight = 0

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        editor.resetTextState()
        Timber.d("onStartInput: restarting=$restarting")
        val isNullType = attribute.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        postRimeJob {
            if (restarting) {
                // when input restarts in the same editor, clear previous composition
                clearComposition()
            }
            setNullInputType(isNullType)
        }
    }

    private val inlineSuggestions by prefs.general.inlineSuggestions

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!inlineSuggestions || !inputDeviceManager.useVirtualKeyboard) return null
        // The system can request inline suggestions while Rime/theme bootstrap
        // is still in progress (ColorManager not initialized); building the
        // request then crashes on the uninitialized color scheme. Defer until
        // the theme is ready.
        if (!ThemeManager.isInitialized) return null
        return InlineSuggestions.createRequest(this)
    }

    @SuppressLint("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inputDeviceManager.useVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInputView: restarting=$restarting")
        InputFeedbackManager.startInput()
        postRimeJob {
            updateRimeOption(this)
        }
        val (useVirtualKeyboard, useCandidatesView) =
            inputDeviceManager.evaluateOnStartInputView(attribute, this)
        if (useVirtualKeyboard) {
            inputView?.startInput(attribute, restarting)
        }
        if (useCandidatesView) {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                workaroundNullCursorAnchorInfo()
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        decorLocationUpdated = false
        inputView?.dismissCandidateActionMenu()
        candidatesView?.dismissCandidateActionMenu()
        inputDeviceManager.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        editor.resetTextState()
        postRimeJob {
            clearComposition()
        }
        InputFeedbackManager.finishInput()
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        // Block typing while an IME package is being replaced/deployed.
        if (ImePackageManager.isActivating()) return true
        // If the engine is not ready (startup/deploy in progress or failed),
        // fall through to the framework instead of swallowing the key forever.
        if (!rime.isReady) return false
        val keyVal = event.toKeyValue()
        if (keyVal.value != RimeKeyMapping.RimeKey_VoidSymbol) {
            val modifiers = event.toKeyModifiers()
            postRimeJob {
                processKey(keyVal, modifiers, isVirtual = false)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (inputDeviceManager.evaluateOnKeyDown(event, this)) {
            decorLocationUpdated = false
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = forwardKeyEvent(event) || super.onKeyUp(keyCode, event)

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceManager.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceManager.evaluateOnUpdateEditorToolType(toolType, this)
    }

    fun switchToPrevIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToLastInputMethod(window.window!!.attributes.token)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToNextInputMethod(window.window!!.attributes.token, false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }
}
