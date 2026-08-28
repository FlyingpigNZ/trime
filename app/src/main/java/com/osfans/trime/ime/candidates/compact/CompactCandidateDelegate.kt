/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeColor
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.unrolled.decoration.FlexboxVerticalDecoration
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import kotlin.math.max

/**
 * Snapshot of one candidate-menu refresh, consumed by the unrolled window to
 * decide whether it must reload.
 *
 * [offset] is the number of candidates currently shown by the compact bar
 * (the unrolled window displays the ones after it), [highlightedIdx] is the
 * current highlighted candidate index, and [version] is a content version
 * that increments whenever the candidate list actually changes. The dedup in
 * BaseUnrolledCandidateWindow must include [version]: after selecting a
 * character the new menu may have exactly the same visible count and
 * highlight as the old one, so comparing offset/highlight alone would
 * wrongly skip the refresh and leave the unrolled window on stale
 * candidates.
 */
data class UnrolledCandidateUpdate(
    val offset: Int,
    val highlightedIdx: Int,
    val version: Int,
)

class CompactCandidateDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    val rime: RimeSession by di.instance()
    val theme: Theme by di.instance()
    private val inputView: InputView by di.instance()
    val bar: InputBarDelegate by di.instance()

    private val fillStyle by AppPrefs.defaultInstance().keyboard.horizontalCandidateMode

    private val maxSpanCountPref by lazy {
        AppPrefs.defaultInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                maxSpanCount
            } else {
                maxSpanCountLandscape
            }
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 0f

    /**
     * (for [CompactCandidateMode.AUTO_FILL] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    /**
     * Content version of the candidate list, bumped in [onCandidateListUpdate]
     * only when the candidates actually differ (compared by content hash).
     * Lets the unrolled window distinguish "relayout of the same menu"
     * (version unchanged) from "a new candidate list" (version changed),
     * even when the visible count and highlight happen to stay the same.
     */
    private var candidatesVersion = 0
    private var lastCandidatesHash = 0

    private val _unrolledCandidateOffset =
        MutableSharedFlow<UnrolledCandidateUpdate>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val unrolledCandidateOffset = _unrolledCandidateOffset.asSharedFlow()

    fun refreshUnrolled(childCount: Int) {
        _unrolledCandidateOffset.tryEmit(
            UnrolledCandidateUpdate(
                offset = childCount,
                highlightedIdx = adapter.highlightedIdx,
                version = candidatesVersion,
            ),
        )
        // Auto-expand purely on the highlighted index exceeding the compact
        // visible count, as in the original feature: whenever the highlight is
        // out of the compact range the unrolled window must reveal it. The
        // Backspace re-popup is prevented upstream — the highlight is reset
        // to -1 when the unrolled window is collapsed (see
        // resetUnrolledHighlight), so a rebuilt menu can never look
        // out-of-bounds right after a collapse.
        val highlighted = adapter.highlightedIdx
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                (adapter.total == childCount),
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesHighlighted to
                (highlighted >= childCount),
        )
    }

    /**
     * Reset the highlighted index to -1. Called when the unrolled window is
     * collapsed: after the user picked a candidate the highlight has no
     * meaning, and resetting it guarantees that the next candidate refresh
     * (e.g. Backspace rebuilding the menu) sees an in-bounds highlight and
     * does not auto-expand the window. No state-machine push is needed here:
     * the next refreshUnrolled() re-pushes both booleans atomically and will
     * read the reset -1.
     */
    fun resetUnrolledHighlight() {
        adapter.resetHighlightedIndex()
    }

    val adapter by lazy {
        CompactCandidateViewAdapter(theme).apply {
            setOnItemClickListener { _, _, position ->
                rime.launchOnReady { it.selectCandidate(position, global = true) }
            }
            setOnItemLongClickListener { _, view, position ->
                inputView.showCandidateActionMenu(position, items[position].text, view, global = true)
                true
            }
        }
    }

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    val layoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            override fun canScrollHorizontally(): Boolean = false

            override fun canScrollVertically(): Boolean = false

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.itemCount) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshUnrolled(cnt)
            }
        }
    }

    private val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.generalStyle.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = ColorManager.getColor(ThemeColor.CANDIDATE_SEPARATOR_COLOR)
        }
    }

    val view by lazy {
        // The previous implementation created an anonymous RecyclerView first
        // whose value was discarded — including its onSizeChanged AUTO_FILL
        // layoutMinWidth logic, which therefore never ran. Keep only the real
        // view.
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(separatorDrawable))
        }
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        val (total, highlighted, candidates) = data

        // Bump the content version only when the candidates actually differ.
        // Keeps relayouts of the same menu deduplicated while guaranteeing a
        // reload after a selection changes the candidate set.
        val hash = candidates.contentHashCode()
        if (hash != lastCandidatesHash) {
            lastCandidatesHash = hash
            candidatesVersion++
        }

        val maxSpanCount = maxSpanCountPref.getValue()

        when (fillStyle) {
            CompactCandidateMode.NEVER_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            CompactCandidateMode.AUTO_FILL -> {
                layoutMinWidth = view.width / maxSpanCount - separatorDrawable.intrinsicWidth
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = candidates.size < maxSpanCount
                secondLayoutPassDone = false
            }
            CompactCandidateMode.ALWAYS_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }

        adapter.updateLayoutParams(layoutMinWidth, layoutFlexGrow)
        adapter.updateCandidates(candidates, total, highlighted)

        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshUnrolled(0)
        }
    }
}
