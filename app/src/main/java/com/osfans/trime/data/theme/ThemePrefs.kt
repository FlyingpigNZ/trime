/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum
import com.osfans.trime.data.prefs.PreferenceDelegateOwner

class ThemePrefs(
    sharedPrefs: SharedPreferences,
) : PreferenceDelegateOwner(sharedPrefs, R.string.theme) {
    val normalModeColor =
        string(
            R.string.normal_mode_color,
            NORMAL_MODE_COLOR,
            "default",
            R.string.normal_mode_color_summary,
        )

    enum class NavbarBackground(
        override val stringRes: Int,
    ) : PreferenceDelegateEnum {
        NONE(R.string.navbar_bkg_none),
        COLOR_ONLY(R.string.navbar_bkg_color_only),
        FULL(R.string.navbar_bkg_full),
    }

    val navbarBackground =
        enum(
            R.string.navbar_background,
            NAVBAR_BACKGROUND,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                NavbarBackground.FULL
            } else {
                NavbarBackground.COLOR_ONLY
            },
            enableUiOn = { Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM },
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                sharedPreferences.edit {
                    remove(this@apply.key)
                }
            }
        }

    val followSystemDayNight =
        switch(
            R.string.follow_system_day_night_color,
            FOLLOW_SYSTEM_DAY_NIGHT,
            false,
        )

    /**
     * Master switch for the on-keyboard pinyin disambiguation overlay — both
     * the T9 first-column strip and the 小鹤双拼14键 first-row strip. Defaults
     * to on; users who replaced the 14-key layout's digit row (or otherwise
     * want the strips hidden) turn it off.
     */
    val pinyinFilter =
        switch(
            R.string.pinyin_filter,
            PINYIN_FILTER,
            true,
            R.string.pinyin_filter_summary,
        )

    companion object {
        const val NORMAL_MODE_COLOR = "normal_mode_color"
        const val FOLLOW_SYSTEM_DAY_NIGHT = "follow_system_day_night"
        const val NAVBAR_BACKGROUND = "navbar_background"
        const val PINYIN_FILTER = "pinyin_filter"
    }
}
