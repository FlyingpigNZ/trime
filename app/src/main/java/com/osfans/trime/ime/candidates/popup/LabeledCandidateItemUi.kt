/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeColor
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
) : Ui {
    private val labelSize = theme.window.foreground.labelFontSize
    private val textSize = theme.window.foreground.textFontSize
    private val commentSize = theme.window.foreground.commentFontSize
    private val labelFont = FontManager.getTypeface("label_font")
    private val textFont = FontManager.getTypeface("candidate_font")
    private val commentFont = FontManager.getTypeface("comment_font")
    private val labelColor = ColorManager.getColor(ThemeColor.LABEL_COLOR)
    private val textColor = ColorManager.getColor(ThemeColor.CANDIDATE_TEXT_COLOR)
    private val commentColor = ColorManager.getColor(ThemeColor.COMMENT_TEXT_COLOR)
    private val highlightLabelColor = ColorManager.getColor(ThemeColor.HILITED_LABEL_COLOR)
    private val highlightCommentTextColor = ColorManager.getColor(ThemeColor.HILITED_COMMENT_TEXT_COLOR)
    private val highlightCandidateTextColor = ColorManager.getColor(ThemeColor.HILITED_CANDIDATE_TEXT_COLOR)
    private val highlightCandidateBackColor = ColorManager.getColor(ThemeColor.HILITED_CANDIDATE_BACK_COLOR)

    override val root =
        textView {
            val v = dp(theme.window.itemPadding.vertical)
            val h = dp(theme.window.itemPadding.horizontal)
            setPadding(h, v, h, v)
        }

    private inline fun SpannableStringBuilder.inSpanWith(
        @ColorInt color: Int,
        textSize: Float,
        typeface: Typeface,
        builderAction: SpannableStringBuilder.() -> Unit,
    ) = inSpans(CandidateItemSpan(color, textSize, typeface), builderAction)

    fun update(
        candidate: CandidateProto,
        highlighted: Boolean,
    ) {
        val labelFg = if (highlighted) highlightLabelColor else labelColor
        val textFg = if (highlighted) highlightCandidateTextColor else textColor
        val commentFg = if (highlighted) highlightCommentTextColor else commentColor
        root.text =
            buildSpannedString {
                inSpanWith(labelFg, ctx.sp(labelSize), labelFont) { append(candidate.label) }
                append(" ")
                inSpanWith(textFg, ctx.sp(textSize), textFont) { append(candidate.text) }
                if (candidate.comment.isNotBlank()) {
                    append(" ")
                    inSpanWith(commentFg, ctx.sp(commentSize), commentFont) { append(candidate.comment) }
                }
            }
        val bg =
            GradientDrawable().apply {
                if (highlighted) {
                    setColor(highlightCandidateBackColor)
                    cornerRadius = ctx.dp(theme.generalStyle.candidateCornerRadius)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
        root.background = bg
    }
}
