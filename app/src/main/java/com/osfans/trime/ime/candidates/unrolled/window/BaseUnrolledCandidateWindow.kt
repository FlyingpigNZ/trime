/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled.window

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeColor
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.CandidatesPagingSource
import com.osfans.trime.ime.candidates.unrolled.PagingCandidateViewAdapter
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateLayout
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import kotlin.math.max

abstract class BaseUnrolledCandidateWindow :
    BoardWindow.NoBarBoardWindow(),
    InputBroadcastReceiver {
    protected val rime: RimeSession by di.instance()
    protected val theme: Theme by di.instance()
    private val inputView: InputView by di.instance()
    private val bar: InputBarDelegate by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val compactCandidate: CompactCandidateDelegate by di.instance()

    private lateinit var lifecycleCoroutineScope: LifecycleCoroutineScope
    private lateinit var candidateLayout: UnrolledCandidateLayout

    protected val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.generalStyle.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = ColorManager.getColor(ThemeColor.CANDIDATE_SEPARATOR_COLOR)
        }
    }

    abstract fun onCreateCandidateLayout(): UnrolledCandidateLayout

    final override fun onCreateView(): View {
        candidateLayout =
            onCreateCandidateLayout().apply {
                recyclerView.apply {
                    // disable item cross-fade animation
                    itemAnimator = null
                }
            }
        return candidateLayout
    }

    abstract val adapter: PagingCandidateViewAdapter
    abstract val layoutManager: RecyclerView.LayoutManager

    private var offsetJob: Job? = null

    private val candidatesPager by lazy {
        Pager(
            config = PagingConfig(
                pageSize = 48,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                CandidatesPagingSource(
                    rime,
                    total = compactCandidate.adapter.total,
                    offset = adapter.offset,
                )
            },
        )
    }

    private var candidatesSubmitJob: Job? = null

    override fun onAttached() {
        lifecycleCoroutineScope = candidateLayout.findViewTreeLifecycleOwner()!!.lifecycleScope
        bar.unrollButtonStateMachine.push(UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesAttached)
        offsetJob =
            lifecycleCoroutineScope.launch {
                // onLayoutCompleted re-emits the same child count on every
                // layout pass; skip identical values so a mere relayout does
                // not reset the scroll position and reload the paging source.
                // The dedup key must include the highlight index and the
                // candidates version: a relayout with the same count but a new
                // highlight, or a new menu with the same count/highlight (e.g.
                // after selecting a character), must still refresh.
                var lastOffset = Int.MIN_VALUE
                var lastHighlight = -1
                var lastVersion = -1
                compactCandidate.unrolledCandidateOffset.collect { update ->
                    val offset = update.offset
                    val highlight = update.highlightedIdx
                    if (offset == lastOffset &&
                        highlight == lastHighlight &&
                        update.version == lastVersion
                    ) {
                        return@collect
                    }
                    lastOffset = offset
                    lastHighlight = highlight
                    lastVersion = update.version
                    if (offset <= 0) {
                        windowManager.attachWindow(KeyboardWindow)
                    } else {
                        candidateLayout.resetPosition()
                        adapter.refreshWith(
                            offset = offset,
                            highlightedIndex = highlight,
                        )
                    }
                }
            }
        candidatesSubmitJob =
            lifecycleCoroutineScope.launch {
                candidatesPager.flow.collectLatest {
                    adapter.submitData(it)
                }
            }
    }

    fun bindCandidateUiViewHolder(holder: CandidateViewHolder) {
        holder.itemView.run {
            setOnClickListener { _ ->
                rime.launchOnReady { it.selectCandidate(holder.idx, global = true) }
            }
            setOnLongClickListener { view ->
                inputView.showCandidateActionMenu(holder.idx, holder.text, view, global = true)
                true
            }
        }
    }

    override fun onDetached() {
        // "Menu empty" is judged by the actual menu state, not by the compact
        // adapter's total compared against the unrolled adapter's offset: the
        // unrolled offset is whatever the last refreshWith wrote (0 on a
        // fresh window), while the compact total is -1 or >= 1 whenever a
        // menu is present, so that comparison is effectively always false and
        // would leave the state machine at ClickToAttachWindow, causing any
        // later candidate update to auto-attach the window again. Use
        // hasMenu, which RimeUiState tracks separately from composition (a
        // menu can exist without composing, e.g. look-up candidates).
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesDetached,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                !rime.uiState.value.hasMenu,
        )
        offsetJob?.cancel()
        candidatesSubmitJob?.cancel()
    }
}
