/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import com.osfans.trime.ime.bar.UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesHighlighted
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.AboutToAttachWindow
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.ClickToAttachWindow
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.ClickToDetachWindow
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.Hidden
import com.osfans.trime.util.BuildTransitionEvent
import com.osfans.trime.util.EventStateMachine
import com.osfans.trime.util.TransitionBuildBlock

/**
 * Drives the unroll button state and the unrolled candidates window.
 *
 * [UnrolledCandidatesUpdated] only drives button visibility/state. The window
 * itself is attached either by the user clicking the button, by
 * [UnrolledCandidatesAttached], or by [AboutToAttachWindow] — which is entered
 * only when the highlighted index just moved outside the compact visible
 * range (a navigation-driven signal, see CompactCandidateDelegate). This
 * restores the "auto-expand when the highlight leaves the compact bar"
 * behavior from 041675a0 without re-attaching the window on every candidate
 * refresh (e.g. pressing Backspace after selecting a character).
 */
object UnrollButtonStateMachine {
    enum class State {
        AboutToAttachWindow,
        ClickToAttachWindow,
        ClickToDetachWindow,
        Hidden,
    }

    enum class BooleanKey : EventStateMachine.BooleanStateKey {
        UnrolledCandidatesEmpty,
        UnrolledCandidatesHighlighted,
    }

    enum class TransitionEvent(
        val builder: TransitionBuildBlock<State, BooleanKey>,
    ) : EventStateMachine.TransitionEvent<State, BooleanKey> by BuildTransitionEvent(builder) {
        UnrolledCandidatesUpdated({
            from(Hidden) transitTo ClickToAttachWindow on (UnrolledCandidatesEmpty to false)
            from(ClickToAttachWindow) transitTo Hidden on (UnrolledCandidatesEmpty to true)
            from(ClickToAttachWindow) transitTo AboutToAttachWindow on (UnrolledCandidatesHighlighted to true)
        }),
        UnrolledCandidatesAttached({
            from(ClickToAttachWindow) transitTo ClickToDetachWindow
            from(AboutToAttachWindow) transitTo ClickToDetachWindow
        }),
        UnrolledCandidatesDetached({
            from(ClickToDetachWindow) transitTo Hidden on (UnrolledCandidatesEmpty to true)
            from(ClickToDetachWindow) transitTo ClickToAttachWindow on (UnrolledCandidatesEmpty to false)
        }),
    }

    fun new(block: (State) -> Unit) = EventStateMachine<State, TransitionEvent, BooleanKey>(
        initialState = Hidden,
        externalBooleanStates =
        mutableMapOf(
            UnrolledCandidatesEmpty to true,
        ),
    ).apply {
        onNewStateListener = block
    }
}
