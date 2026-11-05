/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.broadcast

import android.view.inputmethod.EditorInfo
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.EditorEnterAction
import com.osfans.trime.ime.dependency.InputDependencyManager
import org.kodein.di.instance

class EnterKeyDisplayDelegate {
    private val theme: Theme by InputDependencyManager.getInstance().di.instance()
    private val broadcaster: InputBroadcaster by InputDependencyManager.getInstance().di.instance()

    companion object {
        const val DEFAULT_LABEL = "Enter"
    }

    enum class Mode {
        ACTION_LABEL_NEVER,
        ACTION_LABEL_ONLY,
        ACTION_LABEL_PREFERRED,
        CUSTOM_PREFERRED,
    }

    val mode: Mode = runCatching { Mode.entries[theme.generalStyle.enterLabelMode] }.getOrDefault(Mode.ACTION_LABEL_NEVER)

    var keyLabel: String = DEFAULT_LABEL
        private set

    fun updateLabelOnEditorInfo(info: EditorInfo) {
        updateLabel(info.inputType, info.imeOptions, info.actionId, info.actionLabel)
    }

    /** Updates the visible text before notifying listeners, without repeating unchanged labels. */
    internal fun updateLabel(
        inputType: Int,
        imeOptions: Int,
        actionId: Int,
        actionLabel: CharSequence?,
    ) {
        val action = EditorEnterAction.resolve(inputType, imeOptions, actionId, actionLabel)
        val label =
            EditorEnterAction.label(action, actionLabel, mode.ordinal, theme.generalStyle.enterLabel.default) { themeAction ->
                with(theme.generalStyle.enterLabel) {
                    when (themeAction) {
                        EditorInfo.IME_ACTION_DONE -> done
                        EditorInfo.IME_ACTION_GO -> go
                        EditorInfo.IME_ACTION_NEXT -> next
                        EditorInfo.IME_ACTION_PREVIOUS -> pre
                        EditorInfo.IME_ACTION_SEARCH -> search
                        EditorInfo.IME_ACTION_SEND -> send
                        else -> null
                    }
                }
            }
        if (keyLabel == label) return
        keyLabel = label
        broadcaster.onEnterKeyLabelUpdate(label)
    }
}
