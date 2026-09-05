/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import androidx.annotation.DrawableRes
import com.osfans.trime.core.RimeSchema

sealed class SwitchOptionEntry(
    val label: String,
    @param:DrawableRes
    val icon: Int,
) {
    class Static(label: String, icon: Int, val type: Type) : SwitchOptionEntry(label, icon) {
        enum class Type {
            SchemaList,
            UpdateConfig,
            Keyboard,
        }
    }

    class Custom(
        val switch: RimeSchema.Switch,
        label: String,
        icon: Int,
    ) : SwitchOptionEntry(label, icon)

    companion object {
        /**
         * Build a [Custom] entry from cached option values. Callers must pass
         * the current option snapshot (e.g. `RimeUiState.options` merged with
         * engine reads) so building the list never blocks on the engine.
         */
        fun fromSwitch(
            switch: RimeSchema.Switch,
            options: Map<String, Boolean>,
        ): Custom? {
            val labels = switch.states
            if (labels.size <= 1) return null
            return if (switch.name.isNotEmpty()) {
                if (labels.size != 2) return null
                val (disabledText, enabledText) = labels
                val value = options[switch.name] ?: false
                val label = if (value) "$enabledText → $disabledText" else "$disabledText → $enabledText"
                Custom(switch, label, 0)
            } else {
                val optionList = switch.options
                if (optionList.size != labels.size) return null
                val index = optionList.indexOfFirst { options[it] == true }
                val label = labels[if (index >= 0) index else 0]
                Custom(switch, label, 0)
            }
        }
    }
}
