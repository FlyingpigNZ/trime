// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

/**
 * Builtin fallback color references resolved by [ColorManager].
 *
 * This table is intentionally shared with the validator so that a component
 * package can reference any key the runtime can resolve.
 */
internal val BuiltinFallbackColors =
    mapOf(
        "candidate_text_color" to "text_color",
        "comment_text_color" to "candidate_text_color",
        "border_color" to "back_color",
        "candidate_separator_color" to "border_color",
        "hilited_text_color" to "text_color",
        "hilited_back_color" to "back_color",
        "hilited_candidate_text_color" to "hilited_text_color",
        "hilited_candidate_back_color" to "hilited_back_color",
        "hilited_candidate_button_color" to "hilited_candidate_back_color",
        "hilited_label_color" to "hilited_candidate_text_color",
        "hilited_comment_text_color" to "comment_text_color",
        "hilited_key_back_color" to "hilited_candidate_back_color",
        "hilited_key_text_color" to "hilited_candidate_text_color",
        "hilited_key_symbol_color" to "hilited_comment_text_color",
        "hilited_off_key_back_color" to "hilited_key_back_color",
        "hilited_on_key_back_color" to "hilited_key_back_color",
        "hilited_off_key_text_color" to "hilited_key_text_color",
        "hilited_on_key_text_color" to "hilited_key_text_color",
        "key_back_color" to "back_color",
        "key_border_color" to "border_color",
        "key_text_color" to "candidate_text_color",
        "key_symbol_color" to "comment_text_color",
        "label_color" to "candidate_text_color",
        "off_key_back_color" to "key_back_color",
        "off_key_text_color" to "key_text_color",
        "on_key_back_color" to "hilited_key_back_color",
        "on_key_text_color" to "hilited_key_text_color",
        "popup_back_color" to "key_back_color",
        "popup_text_color" to "key_text_color",
        "hilited_popup_back_color" to "hilited_key_back_color",
        "hilited_popup_text_color" to "hilited_key_text_color",
        "shadow_color" to "border_color",
        "root_background" to "back_color",
        "candidate_background" to "back_color",
        "keyboard_back_color" to "border_color",
        "keyboard_background" to "keyboard_back_color",
        "liquid_keyboard_background" to "keyboard_back_color",
        "text_back_color" to "back_color",
        "long_text_color" to "key_text_color",
        "long_text_back_color" to "key_back_color",
    )
