/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.os.SystemClock
import android.view.KeyEvent
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.core.RimeMessage
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.schema.ImePackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Routes engine messages ([RimeMessage]) to their consumers: commits and
 * preedit go to [ImeEditor], key events are forwarded to the editor (or
 * handled as return), and deploy results drive package/workspace bookkeeping.
 */
class RimeMessageDispatcher(
    private val service: TrimeInputMethodService,
) {
    private val editor get() = service.editor

    fun handle(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CommitTextMessage -> {
                if (!it.data.text.isNullOrEmpty()) {
                    editor.commitText(it.data.text)
                }
            }
            is RimeMessage.InlinePreeditMessage -> {
                editor.updateComposingText(it.data)
            }
            is RimeMessage.KeyMessage ->
                it.data.let msg@{
                    if (it.isVirtual) {
                        when (it.value.value) {
                            RimeKeyMapping.RimeKey_Return -> editor.handleReturnKey()
                            else -> {
                                val keyCode = it.value.keyCode
                                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                                    // recognized keyCode
                                    editor.sendDownUpKeyEvent(
                                        keyCode,
                                        it.modifiers.toMetaState() or editor.meta(
                                            alt = it.modifiers.alt,
                                            shift = it.modifiers.shift,
                                            ctrl = it.modifiers.ctrl,
                                            meta = it.modifiers.meta,
                                        ),
                                    )
                                    if (it.modifiers.ctrl && keyCode == KeyEvent.KEYCODE_C) editor.clearTextSelection()
                                } else {
                                    if (it.value.value > 0) {
                                        runCatching {
                                            editor.commitText(Character.toString(it.value.value))
                                        }.getOrElse { t -> Timber.w(t, "Unhandled Virtual KeyEvent: $it") }
                                    } else {
                                        Timber.w("Unhandled Virtual KeyEvent: $it")
                                    }
                                }
                            }
                        }
                    } else {
                        val keyCode = it.value.keyCode
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            // recognized keyCode
                            val eventTime = SystemClock.uptimeMillis()
                            if (it.modifiers.release) {
                                editor.sendUpKeyEvent(eventTime, keyCode, it.modifiers.toMetaState())
                            } else {
                                editor.sendDownKeyEvent(eventTime, keyCode, it.modifiers.toMetaState())
                            }
                        } else {
                            if (!it.modifiers.release && it.value.value > 0) {
                                runCatching {
                                    editor.commitText(Character.toString(it.value.value))
                                }.getOrElse { t -> Timber.w(t, "Unhandled Rime KeyEvent: $it") }
                            } else {
                                Timber.w("Unhandled Rime KeyEvent: $it")
                            }
                        }
                    }
                }
            is RimeMessage.DeployMessage -> {
                if (it.data == RimeMessage.DeployMessage.State.Success) {
                    // The engine itself may have deployed the current workspace
                    // (e.g. Default during first startup); record that so the
                    // compile service does not redundantly compile it again.
                    // Marker writes and the Default.zip hash run off the main
                    // thread.
                    service.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            ImePackageManager.markCurrentWorkspaceCompiled()
                        }
                        // Always go through ensureDefaultPackageReady: it
                        // restores a usable active theme, and falls back to a
                        // compiled Default when the active workspace's theme is
                        // unusable.
                        ImePackageManager.ensureDefaultPackageReady()
                    }
                }
            }
            else -> {}
        }
    }
}
