// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

/** Typed color keys used by [ColorManager] and UI call sites. */
enum class ThemeColor(val key: String) {
    BACK_COLOR("back_color"),
    CANDIDATE_SEPARATOR_COLOR("candidate_separator_color"),
    CANDIDATE_TEXT_COLOR("candidate_text_color"),
    COMMENT_TEXT_COLOR("comment_text_color"),
    HILITED_CANDIDATE_BACK_COLOR("hilited_candidate_back_color"),
    HILITED_CANDIDATE_TEXT_COLOR("hilited_candidate_text_color"),
    HILITED_COMMENT_TEXT_COLOR("hilited_comment_text_color"),
    HILITED_KEY_TEXT_COLOR("hilited_key_text_color"),
    HILITED_LABEL_COLOR("hilited_label_color"),
    HILITED_OFF_KEY_BACK_COLOR("hilited_off_key_back_color"),
    HILITED_ON_KEY_BACK_COLOR("hilited_on_key_back_color"),
    HILITED_POPUP_BACK_COLOR("hilited_popup_back_color"),
    HILITED_POPUP_TEXT_COLOR("hilited_popup_text_color"),
    HILITED_TEXT_COLOR("hilited_text_color"),
    KEYBOARD_BACKGROUND("keyboard_background"),
    KEY_BORDER_COLOR("key_border_color"),
    KEY_SYMBOL_COLOR("key_symbol_color"),
    KEY_TEXT_COLOR("key_text_color"),
    LABEL_COLOR("label_color"),
    OFF_KEY_BACK_COLOR("off_key_back_color"),
    ON_KEY_BACK_COLOR("on_key_back_color"),
    POPUP_BACK_COLOR("popup_back_color"),
    POPUP_TEXT_COLOR("popup_text_color"),
    TEXT_BACK_COLOR("text_back_color"),
    TEXT_COLOR("text_color"),
    ;

    companion object {
        fun fromKey(key: String): ThemeColor? = entries.firstOrNull { it.key == key }
    }
}
