/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.core.view.children
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.util.DiagnosticLog

// TODO: move layout calculation responsibilities from Keyboard to KeyboardView using ConstraintLayout
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    val popup: PopupDelegate,
    val service: TrimeInputMethodService,
    private val keyboardActionListener: KeyboardActionListener,
    private val enterKeyDisplay: EnterKeyDisplayDelegate,
    private val rime: RimeSession,
) : FrameLayout(context) {
    private companion object {
        const val TAG = "keyboard"
    }

    private val keys get() = keyboard.keys

    /** Last (width, height) pair written to the persistent log, packed. */
    private var lastLoggedSize = 0L

    /** Height scale multiplier for key content (text/icons/offsets). */
    internal val heightScaleFactor: Float
        get() = keyboard.heightScaleFactor

    internal val labelEnter: String
        get() = enterKeyDisplay.keyLabel
    internal val keyTextSize = theme.generalStyle.keyTextSize
    internal val keyLongTextSize = theme.generalStyle.keyLongTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val symbolTextSize = theme.generalStyle.symbolTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val popupOnKeyPress by AppPrefs.defaultInstance().keyboard.popupOnKeyPress

    init {
        buildKeyViews()
    }

    private fun buildKeyViews() {
        removeAllViews()

        keys.forEachIndexed { index, key ->
            val keyView = createKeyView(index, key)
            addView(keyView)
        }
    }

    private fun createKeyView(index: Int, key: Key): KeyView = KeyView(context, key = key, keyboard = keyboard, keyboardView = this, keyboardActionListener = keyboardActionListener, rime = rime).apply {
        id = index

        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        layoutParams = LayoutParams(totalWidth, key.height)

        translationX = (key.x - key.extraWidthLeft).toFloat()
        translationY = key.y.toFloat()

        setPadding(
            keyboard.horizontalGap / 2 + key.extraWidthLeft,
            keyboard.verticalGap / 2,
            keyboard.horizontalGap / 2 + key.extraWidthRight,
            keyboard.verticalGap / 2,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DiagnosticLog.i(TAG, "view attached ${sizeRecord()}")
    }

    override fun onDetachedFromWindow() {
        // Detaching happens when the window is torn down / the keyboard is
        // swapped; a detach+reattach of an unchanged keyboard is a window
        // rebuild, not a keyboard change.
        DiagnosticLog.i(TAG, "view detached ${sizeRecord()}")
        super.onDetachedFromWindow()
    }

    /**
     * Log every *drawn* size once, together with the model size it was built
     * from.
     *
     * If a shrunken keyboard is ours, it shows up here as a view smaller than
     * its model (or a model smaller than the container). If it never shows up
     * here while the user still sees one, the shrinking came from outside this
     * view — a window transition / compositor transform — and no layout change
     * of ours can explain it.
     */
    override fun dispatchDraw(canvas: Canvas) {
        val packed = (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)
        if (packed != lastLoggedSize) {
            lastLoggedSize = packed
            DiagnosticLog.i(TAG, "view drawn ${sizeRecord()}")
        }
        super.dispatchDraw(canvas)
    }

    private fun sizeRecord(): String = "view=${width}x$height model=${keyboard.minWidth}x${keyboard.height} keys=${keyboard.keys.size}"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = keyboard.minWidth + paddingLeft + paddingRight
        val fullHeight = keyboard.height + paddingTop + paddingBottom

        val measuredWidth = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            fullWidth,
        )

        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, fullHeight)
    }

    fun invalidateAllKeys() {
        children.forEach { it.invalidate() }
    }

    fun invalidateKeyByIndex(index: Int) {
        getChildAt(index)?.invalidate()
    }

    val isCapsOn: Boolean
        get() = keyboard.mShiftKey?.isOn == true

    fun onDetach() {
        popup.dismissAll()
    }
}
