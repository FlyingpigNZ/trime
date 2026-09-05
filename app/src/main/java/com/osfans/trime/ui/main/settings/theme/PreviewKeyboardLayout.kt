// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.theme

/**
 * Pure (Android-free) keyboard row layout used by the background-editor
 * preview.
 *
 * The real IME builds [com.osfans.trime.ime.keyboard.Keyboard] against the
 * screen width and the `preset_keyboards` entry, wrapping the flat key list
 * into rows in weight space (one row = up to [MAX_TOTAL_WEIGHT] weight units).
 * The editor preview has no Rime session and no IME view, so this class
 * re-implements exactly that wrapping pass over the same data so the preview
 * shows the same rows/keys/widths the user types on. It is kept free of
 * Android imports so the wrap logic is unit-testable on the JVM.
 */
internal data class PreviewKeyInput(
    /** Horizontal width weight (0 = the keyboard's default key width). */
    val widthWeight: Float,
    /** Row height in dp when this key opens a row; 0 = the default row height. */
    val heightDp: Float,
    /** Whether this key has a click action (spacers advance but are not drawn). */
    val clickable: Boolean,
)

/** One drawn key inside a [PreviewKeyboardLayout.Row]. */
internal data class PreviewPlacedKey(
    /** Index into the original [PreviewKeyInput] list. */
    val inputIndex: Int,
    /** Row-relative horizontal start, in weight units (0..[MAX_TOTAL_WEIGHT]). */
    val leftWeight: Float,
    /** Horizontal width in weight units. */
    val widthWeight: Float,
) {
    val rightWeight: Float
        get() = leftWeight + widthWeight
}

internal class PreviewKeyboardLayout(
    val rows: List<Row>,
) {
    data class Row(
        val keys: List<PreviewPlacedKey>,
        /** Sum of width weights of every key (spacers included) in this row. */
        val totalWidthWeight: Float,
        /** Raw row height in dp before it is scaled into the keyboard band. */
        val rawHeightDp: Float,
    ) {
        val isEmpty: Boolean
            get() = keys.isEmpty()
    }

    val isEmpty: Boolean
        get() = rows.all { it.isEmpty }

    companion object {
        /** Same row-width basis as [com.osfans.trime.ime.keyboard.Keyboard]. */
        const val MAX_TOTAL_WEIGHT = 100f

        /**
         * Wrap a flat key list into rows, mirroring the IME's two-pass layout:
         * the first pass computes row breaks/heights, the second places every
         * key. Fixed-point scale keeps weight arithmetic exact like the px
         * arithmetic of the IME (both truncate to whole units per key).
         */
        fun build(
            keys: List<PreviewKeyInput>,
            defaultWidthWeight: Float,
            defaultRowHeightDp: Float,
            maxColumns: Int,
        ): PreviewKeyboardLayout {
            if (keys.isEmpty()) return PreviewKeyboardLayout(emptyList())

            fun keyWidthWeight(key: PreviewKeyInput): Float = if (key.widthWeight == 0f && key.clickable) {
                defaultWidthWeight.takeIf { it > 0f } ?: key.widthWeight
            } else {
                key.widthWeight
            }

            val limitColumns = if (maxColumns == -1) Int.MAX_VALUE else maxColumns
            val totalUnits = (MAX_TOTAL_WEIGHT * FIXED_POINT).toInt()

            // Pass 1: row breaks, row widths and row heights.
            val rowWidthWeightTotal = mutableListOf<Float>()
            val rowRawHeightDp = mutableListOf<Float>()
            var x = 0
            var column = 0
            var rowHeight = defaultRowHeightDp
            var totalKeyWidth = 0f
            for (key in keys) {
                val widthWeight = keyWidthWeight(key)
                val widthUnits = (widthWeight * FIXED_POINT).toInt()
                if (column >= limitColumns || x + widthUnits > totalUnits) {
                    rowWidthWeightTotal.add(totalKeyWidth)
                    rowRawHeightDp.add(rowHeight)
                    x = 0
                    column = 0
                    totalKeyWidth = 0f
                }
                if (column == 0) {
                    rowHeight = if (key.heightDp > 0f) key.heightDp else defaultRowHeightDp
                }
                totalKeyWidth += widthWeight
                if (key.clickable) column++
                x += widthUnits
            }
            rowWidthWeightTotal.add(totalKeyWidth)
            rowRawHeightDp.add(rowHeight)

            // Pass 2: place the clickable keys row by row.
            val rows = MutableList(rowRawHeightDp.size) { rowIndex ->
                Row(emptyList(), rowWidthWeightTotal[rowIndex], rowRawHeightDp[rowIndex])
            }
            var row = 0
            x = 0
            column = 0
            val placed = mutableListOf<PreviewPlacedKey>()
            keys.forEachIndexed { index, key ->
                val widthWeight = keyWidthWeight(key)
                val widthUnits = (widthWeight * FIXED_POINT).toInt()
                if (column >= limitColumns || x + widthUnits > totalUnits) {
                    rows[row] = rows[row].copy(keys = placed.toList())
                    placed.clear()
                    row++
                    x = 0
                    column = 0
                }
                if (key.clickable) {
                    // Mirror the IME's right-edge rounding: a trailing gap of at
                    // most one weight unit is absorbed by the last key so rows
                    // stretch flush to the edge.
                    val rightGap = totalUnits - x - widthUnits
                    val placedWidthUnits =
                        if (rightGap >= 0 && rightGap <= FIXED_POINT) totalUnits - x else widthUnits
                    placed += PreviewPlacedKey(
                        index,
                        x / FIXED_POINT.toFloat(),
                        placedWidthUnits / FIXED_POINT.toFloat(),
                    )
                    x += placedWidthUnits
                    column++
                } else {
                    x += widthUnits
                }
            }
            rows[row] = rows[row].copy(keys = placed.toList())
            // Empty rows (a wrap can open a row that only holds spacers) are
            // kept: the IME still spends row height on them, so dropping them
            // would shift every following row.
            return PreviewKeyboardLayout(rows)
        }

        /** Fixed-point multiplier used for the weight arithmetic. */
        private const val FIXED_POINT = 1000
    }
}
