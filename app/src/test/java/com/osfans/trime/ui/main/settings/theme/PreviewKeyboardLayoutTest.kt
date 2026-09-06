// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.theme

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PreviewKeyboardLayoutTest :
    StringSpec({
        "one row of ten full-width keys" {
            val layout = build(10) { clickable(10f) }

            layout.rows.size shouldBe 1
            val row = layout.rows[0]
            row.keys.size shouldBe 10
            row.totalWidthWeight shouldBe 100f
            row.keys.first().leftWeight shouldBe 0f
            row.keys.last().rightWeight shouldBe 100f
        }

        "keys without explicit width use the keyboard default width" {
            val layout =
                PreviewKeyboardLayout.build(
                    keys = List(8) { clickable(0f) },
                    defaultWidthWeight = 12.5f,
                    defaultRowHeightDp = 44f,
                    maxColumns = -1,
                )

            layout.rows.size shouldBe 1
            layout.rows[0].keys.size shouldBe 8
            layout.rows[0].keys.all { it.widthWeight == 12.5f } shouldBe true
        }

        "spacer keys keep their width but are not placed" {
            // A 5-weight spacer opens the second row (5 + 10 letter keys …) and
            // a trailing 5-weight spacer closes it: 5 + 90 + 5 = 100.
            val specs =
                buildList {
                    repeat(10) { add(clickable(10f)) }
                    add(spacer(5f))
                    repeat(9) { add(clickable(10f)) }
                    add(spacer(5f))
                }
            val layout = build(specs)

            layout.rows.size shouldBe 2
            layout.rows[0].keys.size shouldBe 10
            layout.rows[1].keys.size shouldBe 9
            layout.rows[1].keys.first().leftWeight shouldBe 5f
            layout.rows[1].keys.last().rightWeight shouldBe 95f
            layout.rows[1].totalWidthWeight shouldBe 100f
        }

        "max columns caps a row before the width does" {
            val layout = build(List(12) { clickable(10f) }, maxColumns = 10)

            layout.rows.size shouldBe 2
            layout.rows[0].keys.size shouldBe 10
            layout.rows[1].keys.size shouldBe 2
        }

        "a trailing sub-weight gap is absorbed by the last key" {
            val layout = build(List(3) { clickable(33.3f) })

            layout.rows.size shouldBe 1
            val keys = layout.rows[0].keys
            keys[0].widthWeight shouldBe 33.3f
            keys[2].widthWeight shouldBe 33.4f
            keys[2].rightWeight shouldBe 100f
        }

        "a key that would overflow wraps to the next row" {
            // Row 0: clickable 50 + spacer 50 (ends exactly at 100); the second
            // clickable 50 then starts row 1.
            val specs = listOf(clickable(50f), spacer(50f), clickable(50f))
            val layout = build(specs)

            layout.rows.size shouldBe 2
            layout.rows[0].keys.single().leftWeight shouldBe 0f
            layout.rows[1].keys.single().leftWeight shouldBe 0f
            layout.rows[1].keys.single().widthWeight shouldBe 50f
        }

        "per-key row heights survive scaling inputs" {
            val specs = listOf(clickable(50f, height = 60f), clickable(50f, height = 40f))
            val layout = build(specs)

            layout.rows.single().keys.size shouldBe 2
            layout.rows.single().rawHeightDp shouldBe 60f
        }

        "an empty keyboard has no rows" {
            PreviewKeyboardLayout.build(emptyList(), 10f, 44f, -1).isEmpty shouldBe true
        }
    })

private fun build(
    specs: List<PreviewKeyInput>,
    maxColumns: Int = -1,
    defaultWidth: Float = 10f,
) = PreviewKeyboardLayout.build(
    keys = specs,
    defaultWidthWeight = defaultWidth,
    defaultRowHeightDp = 44f,
    maxColumns = maxColumns,
)

private fun build(
    count: Int,
    spec: () -> PreviewKeyInput,
) = build(List(count) { spec() })

private fun clickable(
    width: Float,
    height: Float = 0f,
) = PreviewKeyInput(width, height, clickable = true)

private fun spacer(width: Float) = PreviewKeyInput(width, 0f, clickable = false)
