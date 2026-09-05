// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test
    fun appLaunchesAndLoadsDefaultTheme() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
        assertTrue(ThemeManager.activeTheme.presetKeyboards.isNotEmpty())
        assertTrue(ThemeManager.activeTheme.colorSchemes.isNotEmpty())
    }
}
