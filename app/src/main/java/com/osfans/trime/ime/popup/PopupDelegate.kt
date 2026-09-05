/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.popup

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.KeyboardSwitcher
import com.osfans.trime.ime.keyboard.UiScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList

class PopupDelegate {
    private val context: Context by InputDependencyManager.getInstance().di.instance()
    private val theme: Theme by InputDependencyManager.getInstance().di.instance()
    private val service: TrimeInputMethodService by InputDependencyManager.getInstance().di.instance()
    private val keyboardSwitcher: KeyboardSwitcher by InputDependencyManager.getInstance().di.instance()

    private val showingEntryUi = HashMap<Int, PopupEntryUi>()
    private val dismissJobs = HashMap<Int, Job>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<Int, PopupContainerUi>()

    private val popupBottomMargin by lazy {
        context.dp((theme.generalStyle.popupBottomMargin * UiScale.factor).toInt())
    }
    private val popupWidth by lazy {
        context.dp((theme.generalStyle.popupWidth * UiScale.factor).toInt())
    }
    private val popupHeight by lazy {
        context.dp((theme.generalStyle.popupHeight * UiScale.factor).toInt())
    }
    private val popupKeyHeight by lazy {
        context.dp((theme.generalStyle.popupKeyHeight * UiScale.factor).toInt())
    }
    private val popupRadius by lazy {
        context.dp(theme.generalStyle.roundCorner * UiScale.factor)
    }
    private val hideThreshold = 100L

    private val rootLocation = intArrayOf(0, 0)
    private val rootBounds: Rect = Rect()

    val root by lazy {
        context.frameLayout {
            // we want (0, 0) at top left
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false

            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val (x, y) = rootLocation.also { v.getLocationInWindow(it) }
                val width = right - left
                val height = bottom - top
                rootBounds.set(x, y, x + width, y + height)
            }
        }
    }

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        showingEntryUi[viewId]?.apply {
            dismissJobs[viewId]?.also {
                dismissJobs.remove(viewId)?.cancel()
            }
            lastShowTime = System.currentTimeMillis()
            setText(content)
            return
        }
        val popup = (
            freeEntryUi.poll()
                ?: PopupEntryUi(context, theme, popupKeyHeight, popupRadius)
            ).apply {
            lastShowTime = System.currentTimeMillis()
            setText(content)
        }
        placePopup(popup, bounds)
        showingEntryUi[viewId] = popup
    }

    private fun placePopup(ui: PopupEntryUi, bounds: Rect) {
        val v = ui.root
        if (v.parent == root) return
        (v.parent as? ViewGroup)?.removeView(v)
        root.apply {
            add(
                v,
                lParams(popupWidth, popupHeight) {
                    // align popup bottom with key border bottom
                    topMargin = bounds.bottom - popupHeight - popupBottomMargin
                    leftMargin = (bounds.left + bounds.right - popupWidth) / 2
                },
            )
        }
    }

    private fun updatePopup(viewId: Int, content: String) {
        showingEntryUi[viewId]?.setText(content)
    }

    private fun showKeyboard(viewId: Int, keys: List<String>, bounds: Rect) {
        // clear popup preview text         OR create empty popup preview
        showingEntryUi[viewId]?.setText("") ?: showPopup(viewId, "", bounds)
        reallyShowKeyboard(viewId, keys, bounds)
    }

    private fun reallyShowKeyboard(viewId: Int, keys: List<String>, bounds: Rect) {
        val labels = keys
        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            keyboardSwitcher,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            popupWidth,
            popupKeyHeight,
            // position popup keyboard higher, because of [^1]
            popupHeight + popupBottomMargin,
            keys,
            labels,
        )
        showPopupContainer(viewId, keyboardUi)
    }

    private fun showPopupContainer(viewId: Int, ui: PopupContainerUi) {
        root.apply {
            add(
                ui.root,
                lParams {
                    leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left
                    topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top
                },
            )
        }
        showingContainerUi[viewId] = ui
    }

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean = showingContainerUi[viewId]?.changeFocus(x, y) ?: false

    private fun triggerFocused(viewId: Int): String? = showingContainerUi[viewId]?.onTrigger()

    private fun dismissPopup(viewId: Int) {
        dismissPopupContainer(viewId)
        // Cancel a pending delayed dismiss first: scheduling a second job for
        // the same viewId would run dismissPopupEntry twice and enqueue the
        // same PopupEntryUi into the free pool twice, so later keys could
        // reuse a view that is still attached elsewhere.
        dismissJobs.remove(viewId)?.cancel()
        showingEntryUi[viewId]?.also {
            val timeLeft = it.lastShowTime + hideThreshold - System.currentTimeMillis()
            if (timeLeft <= 0L) {
                dismissPopupEntry(viewId, it)
            } else {
                dismissJobs[viewId] = service.lifecycleScope.launch {
                    delay(timeLeft)
                    dismissPopupEntry(viewId, it)
                    dismissJobs.remove(viewId)
                }
            }
        }
    }

    private fun dismissPopupContainer(viewId: Int) {
        showingContainerUi[viewId]?.also {
            showingContainerUi.remove(viewId)
            root.removeView(it.root)
        }
    }

    private fun dismissPopupEntry(viewId: Int, popup: PopupEntryUi) {
        // Idempotent: only free the entry while it is still the shown one, so
        // a stale delayed job cannot enqueue it into the free pool twice.
        if (showingEntryUi[viewId] !== popup) return
        showingEntryUi.remove(viewId)
        root.removeView(popup.root)
        freeEntryUi.add(popup)
    }

    fun dismissAll() {
        // avoid modifying collection while iterating
        dismissJobs.forEach { (_, job) ->
            job.cancel()
        }
        dismissJobs.clear()
        // too
        showingContainerUi.forEach { (_, container) ->
            root.removeView(container.root)
        }
        showingContainerUi.clear()
        // too too
        showingEntryUi.forEach { (_, entry) ->
            root.removeView(entry.root)
            freeEntryUi.add(entry)
        }
        showingEntryUi.clear()
    }

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keys, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }
}
