/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import com.osfans.trime.ime.bar.UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesHighlighted
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.ClickToAttachWindow
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.ClickToDetachWindow
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.Hidden
import com.osfans.trime.util.BuildTransitionEvent
import com.osfans.trime.util.EventStateMachine
import com.osfans.trime.util.TransitionBuildBlock

object UnrollButtonStateMachine {
    enum class State {
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
            // 注意：不再有 ClickToAttachWindow -> ClickToDetachWindow 规则。
            // 候选菜单更新只负责按钮的显隐/形态，绝不自动挂载 unrolled
            // 窗口。窗口只由用户点击 unroll 按钮（setUnrollButtonToAttach /
            // setUnrollButtonToDetach 的点击回调）或 UnrolledCandidatesAttached
            // 显式挂载。否则选完 unrolled 单字、收起窗口后按 Backspace
            // 重建菜单时，unrolled 窗口会"自己弹出来"。
        }),
        UnrolledCandidatesAttached({
            from(ClickToAttachWindow) transitTo ClickToDetachWindow
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
