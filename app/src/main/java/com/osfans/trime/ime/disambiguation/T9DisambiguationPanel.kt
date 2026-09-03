/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.disambiguation

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeColor
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.keyboard.Keyboard
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.recyclerview.recyclerView

/**
 * The T9 pinyin disambiguation column (feature ②).
 *
 * A floating panel sized to the keyboard's first column (the punctuation key
 * column on the T9 keyboard) that lists every legal pinyin parse of the
 * current digit input.
 *
 * Touch handling: the panel is added as the **topmost** child of the
 * keyboard's FrameLayout, so Android dispatches touches that land on it to the
 * panel first. The inner RecyclerView consumes taps (item clicks) and drags
 * (vertical scrolling) and returns true, so the covered punctuation keys below
 * never receive the event — they cannot fire while the panel is showing.
 */
@SuppressLint("ViewConstructor")
class T9DisambiguationPanel(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    private val onPick: (String) -> Unit,
) : FrameLayout(context) {

    private val adapter = T9DisambiguationAdapter(theme, keyboard.heightScaleFactor) { pinyin ->
        onPick(pinyin)
    }

    private val list =
        recyclerView {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@T9DisambiguationPanel.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(
                        recyclerView: RecyclerView,
                        newState: Int,
                    ) {
                        // Confirm the user's drag with the same press haptic as a
                        // key tap, once per drag start (not during the inertial
                        // fling). Skip when there is nothing to scroll — the
                        // short candidate lists fit without scrolling.
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING &&
                            (recyclerView.canScrollVertically(1) || recyclerView.canScrollVertically(-1))
                        ) {
                            InputFeedbackManager.keyPressVibrate(recyclerView)
                        }
                    }
                },
            )
        }

    init {
        val firstColumnWidth = keyboard.firstColumnWidth()
        add(
            list,
            lParams(firstColumnWidth, keyboard.height) {
                gravity = Gravity.START or Gravity.TOP
            },
        )
        // The panel overlays the keyboard's first (punctuation) column. It is
        // deliberately transparent: the controller suppresses that column's
        // keys entirely (see KeyView.onDraw / Keyboard.pinyinOverlayVisible),
        // so neither the button shapes nor their glyphs show through behind
        // the panel — the pinyin text in the adapter is the only content, over
        // the plain keyboard backdrop. We draw nothing here.
        background = null
    }

    /**
     * Submit a new list of pinyin parses. Empty input hides the panel.
     *
     * Every new submit restarts the list at the top: after a pick the panel
     * re-decodes the remaining digits, and the next candidate batch must start
     * at the first row rather than staying at the previously scrolled/selected
     * position.
     */
    fun submitSequences(sequences: List<String>) {
        adapter.submitList(sequences)
        visibility = if (sequences.isEmpty()) View.GONE else View.VISIBLE
        if (sequences.isNotEmpty()) {
            list.scrollToPosition(0)
        }
    }

    fun hide() {
        visibility = View.GONE
        adapter.submitList(emptyList())
    }
}

/** First-column width of the keyboard: the x-span of keys with column == 0. */
private fun Keyboard.firstColumnWidth(): Int {
    val columnZeroKeys = keys.filter { it.column == 0 }
    if (columnZeroKeys.isEmpty()) return minWidth
    return columnZeroKeys.maxOf { it.x + it.width }
}

class T9DisambiguationAdapter(
    private val theme: Theme,
    private val heightScaleFactor: Float,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<T9DisambiguationAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()

    fun submitList(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: android.view.ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val text = TextView(parent.context).apply {
            // Match the key labels: KeyView scales its text/offsets by the
            // keyboard height-scale factor, so the pinyin items must do the
            // same or they drift out of step when the keyboard is scaled.
            textSize = theme.generalStyle.keyTextSize * heightScaleFactor
            typeface = FontManager.getTypeface("key_font")
            gravity = Gravity.CENTER
            setTextColor(ColorManager.getColor(ThemeColor.KEY_TEXT_COLOR))
            setPadding(
                dp(4),
                (dp(6) * heightScaleFactor).toInt(),
                dp(4),
                (dp(6) * heightScaleFactor).toInt(),
            )
            // Fill the panel (first-column) width so Gravity.CENTER actually
            // centers the pinyin across the column. Without explicit
            // MATCH_PARENT width, the default WRAP_CONTENT makes the item only
            // as wide as its text, so the text renders left-justified.
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            )
        }
        return ViewHolder(text)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = items[position]
        holder.text.text = item
        holder.text.setOnClickListener {
            // The panel item is a plain TextView, not a GestureFrame, so it gets
            // no key-press vibration/sound on its own. Picking a pinyin is a
            // key-like action, so play the same press feedback the keyboard
            // layer uses (each call respects its own user preference).
            InputFeedbackManager.keyPressVibrate(holder.text)
            InputFeedbackManager.keyPressSound()
            onPick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}
