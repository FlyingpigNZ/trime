/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import kotlinx.coroutines.launch

class ThemeSettingsFragment : PreferenceDelegateFragment(ThemeManager.prefs) {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("normal_mode_color")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ColorPickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
        preferenceScreen?.addPreference(
            Preference(requireContext()).apply {
                key = KEY_BACKGROUND_EDITOR
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setTitle(R.string.keyboard_background_editor)
                setSummary(R.string.keyboard_background_editor_summary)
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), KeyboardBackgroundEditorActivity::class.java))
                    true
                }
            },
        )
        // UX decision: the background editor is the second entry (right after
        // the color picker), not the last one — a code-added preference lands
        // at the end, so re-order the flat screen children here.
        preferenceScreen?.moveKeyboardBackgroundEntryToSecondPosition()
    }

    private fun PreferenceScreen.moveKeyboardBackgroundEntryToSecondPosition() {
        // Code-created preference groups sort children by each preference's
        // `order` (orderingFromXml defaults to true), so re-adding in a
        // different sequence is a no-op: the order field must be rewritten.
        val children = (0 until preferenceCount).map { getPreference(it) }
        val editorIndex = children.indexOfFirst { it.key == KEY_BACKGROUND_EDITOR }
        if (editorIndex == -1) return
        val editor = children[editorIndex]
        val withoutEditor = children.filterNot { it.key == KEY_BACKGROUND_EDITOR }
        // Put the background editor directly below the color picker
        // ("normal_mode_color"), keeping every other entry's relative order.
        val anchorIndex = withoutEditor.indexOfFirst { it.key == KEY_COLOR_PICKER }
        val anchor = anchorIndex.coerceAtLeast(0)
        val reordered = withoutEditor.take(anchor + 1) + editor + withoutEditor.drop(anchor + 1)
        children.forEach { removePreference(it) }
        reordered.forEachIndexed { position, pref ->
            pref.order = (position + 1) * PREFERENCE_ORDER_STEP
        }
        reordered.forEach { addPreference(it) }
    }

    companion object {
        private const val KEY_BACKGROUND_EDITOR = "keyboard_background_editor"
        private const val KEY_COLOR_PICKER = "normal_mode_color"

        /** Gap between explicit preference orders, so future insertions can slot between. */
        private const val PREFERENCE_ORDER_STEP = 10
    }
}
