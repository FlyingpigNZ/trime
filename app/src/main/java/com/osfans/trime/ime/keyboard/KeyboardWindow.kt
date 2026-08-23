// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.kodein.di.instance
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

    companion object : ResidentWindow.Key

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val cachedKeyboardViews = mutableMapOf<String, KeyboardView>()
    private val currentKeyboard: Keyboard? get() = switcher.currentKeyboard
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboardViews[switcher.currentKeyboardId]

    private val keyboardActionListener = commonKeyboardActionListener.listener

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
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
        detachCurrentView()
        cachedKeyboardViews.clear()
        switcher.invalidateKeyboardCache()
        attachKeyboard(switcher.currentKeyboardId.ifEmpty { ".default" })
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
        switchKeyboard(".default")
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
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
    }

    override fun onDetached() {
        currentKeyboardView?.onDetach()
    }
}
