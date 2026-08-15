// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import com.osfans.trime.core.RimeUiState

/**
 * Legacy global bridge for view-level consumers ([ToolButton],
 * [PopupKeyboardUi], [CommonKeyboardActionListener]) that are not part of the
 * DI graph.
 *
 * Kept only until [KeyAction]'s label/text computation becomes a pure function
 * of a status snapshot (see refactor plan item 10); then these consumers no
 * longer need the current keyboard and this object is deleted.
 */
@Deprecated("Use KeyboardSwitcher via DI; migrate view consumers to status-snapshot functions")
object KeyboardSwitcherLegacy {
    lateinit var currentKeyboard: Keyboard

    /** Latest engine state snapshot, published by [KeyboardSwitcher]. */
    lateinit var currentUiState: RimeUiState
}
