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
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.Keyboard
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.recyclerview.recyclerView

/**
 * The pinyin disambiguation strip (feature ②).
 *
 * A floating panel sized to the keyboard strip it covers that lists every
 * legal pinyin parse of the current key code:
 *
 * - [Keyboard.PinyinOverlay.FIRST_COLUMN] (T9 keyboards): a vertical column
 *   over the first punctuation-key column;
 * - [Keyboard.PinyinOverlay.FIRST_ROW] (小鹤双拼14键 keyboards): a horizontal
 *   bar over the first (digit-key) row.
 *
 * Touch handling: the panel is added as the **topmost** child of the
 * keyboard's FrameLayout, so Android dispatches touches that land on it to the
 * panel first. The inner RecyclerView consumes taps (item clicks) and drags
 * (scrolling) and returns true, so the covered keys below never receive the
 * event — they cannot fire while the panel is showing.
 */
@SuppressLint("ViewConstructor")
class PinyinDisambiguationPanel(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    private val overlay: Keyboard.PinyinOverlay,
    private val onPick: (String) -> Unit,
) : FrameLayout(context) {

    init {
        require(overlay != Keyboard.PinyinOverlay.NONE) { "panel needs a key strip to cover" }
    }

    private val horizontal = overlay == Keyboard.PinyinOverlay.FIRST_ROW

    private val adapter = PinyinDisambiguationAdapter(theme, keyboard.heightScaleFactor, horizontal) { pinyin ->
        onPick(pinyin)
    }

    private val list =
        recyclerView {
            layoutManager =
                LinearLayoutManager(context, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
            this.adapter = this@PinyinDisambiguationPanel.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
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
                        val canScroll = recyclerView.canScrollVertically(1) ||
                            recyclerView.canScrollVertically(-1) ||
                            recyclerView.canScrollHorizontally(1) ||
                            recyclerView.canScrollHorizontally(-1)
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING && canScroll) {
                            InputFeedbackManager.keyPressVibrate(recyclerView)
                        }
                    }
                },
            )
        }

    init {
        add(
            list,
            lParams(keyboard.overlayWidth(overlay), keyboard.overlayHeight(overlay)) {
                gravity = Gravity.START or Gravity.TOP
            },
        )
        // The panel overlays the covered key strip. It is deliberately
        // transparent: the controller suppresses that strip's keys entirely
        // (see KeyView.onDraw / Keyboard.pinyinOverlay), so neither the button
        // shapes nor their glyphs show through behind the panel — the pinyin
        // text in the adapter is the only content, over the plain keyboard
        // backdrop. We draw nothing here.
        background = null
    }

    /**
     * Submit a new list of pinyin parses. Empty input hides the panel.
     *
     * Every new submit restarts the list at the beginning: after a pick the
     * panel re-decodes the remaining code, and the next candidate batch must
     * start at the first item rather than staying at the previously
     * scrolled/selected position.
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

/** The key strip that [overlay] covers, or the whole keyboard when absent. */
private fun Keyboard.overlayKeys(overlay: Keyboard.PinyinOverlay): List<Key> = when (overlay) {
    Keyboard.PinyinOverlay.FIRST_COLUMN -> keys.filter { it.column == 0 }
    Keyboard.PinyinOverlay.FIRST_ROW -> keys.filter { it.row == 0 }
    Keyboard.PinyinOverlay.NONE -> emptyList()
}

private fun Keyboard.overlayWidth(overlay: Keyboard.PinyinOverlay): Int = when (overlay) {
    Keyboard.PinyinOverlay.FIRST_COLUMN -> firstColumnWidth()
    Keyboard.PinyinOverlay.FIRST_ROW -> firstRowWidth()
    Keyboard.PinyinOverlay.NONE -> minWidth
}

private fun Keyboard.overlayHeight(overlay: Keyboard.PinyinOverlay): Int = when (overlay) {
    Keyboard.PinyinOverlay.FIRST_COLUMN -> height
    Keyboard.PinyinOverlay.FIRST_ROW -> firstRowHeight()
    Keyboard.PinyinOverlay.NONE -> height
}

/** First-column width of the keyboard: the x-span of keys with column == 0. */
private fun Keyboard.firstColumnWidth(): Int {
    val columnZeroKeys = overlayKeys(Keyboard.PinyinOverlay.FIRST_COLUMN)
    if (columnZeroKeys.isEmpty()) return minWidth
    return columnZeroKeys.maxOf { it.x + it.width }
}

/** First-row width of the keyboard: the x-span of keys with row == 0. */
private fun Keyboard.firstRowWidth(): Int {
    val rowZeroKeys = overlayKeys(Keyboard.PinyinOverlay.FIRST_ROW)
    if (rowZeroKeys.isEmpty()) return minWidth
    return rowZeroKeys.maxOf { it.x + it.width }
}

/** First-row height of the keyboard: the y-span of keys with row == 0. */
private fun Keyboard.firstRowHeight(): Int {
    val rowZeroKeys = overlayKeys(Keyboard.PinyinOverlay.FIRST_ROW)
    if (rowZeroKeys.isEmpty()) return 0
    val bottom = rowZeroKeys.maxOf { it.y + it.height }
    val top = rowZeroKeys.minOf { it.y }
    return (bottom - top).coerceAtLeast(0)
}

class PinyinDisambiguationAdapter(
    private val theme: Theme,
    private val heightScaleFactor: Float,
    private val horizontal: Boolean,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<PinyinDisambiguationAdapter.ViewHolder>() {

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
            val horizontalPad = if (horizontal) dp(8) else dp(4)
            val verticalPad = (dp(6) * heightScaleFactor).toInt()
            setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
            layoutParams =
                if (horizontal) {
                    // Fill the row height so Gravity.CENTER actually centers the
                    // pinyin vertically inside the one-row bar.
                    RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                        RecyclerView.LayoutParams.MATCH_PARENT,
                    )
                } else {
                    // Fill the panel (first-column) width so Gravity.CENTER
                    // actually centers the pinyin across the column.
                    RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                    )
                }
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
