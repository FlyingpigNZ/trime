/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

/**
 * Pure-Kotlin options the engine needs from the UI layer.
 *
 * The engine must not know about Android preferences; the UI layer (e.g.
 * [com.osfans.trime.daemon.RimeDaemon]) supplies this via the constructor.
 */
interface InputOptions {
    /** How the composition should be presented as inline text in the target field. */
    val inlinePreeditMode: InlinePreeditStyle

    /** Whether to show a short "En"/schema-abbreviation tip when ascii mode toggles. */
    val asciiSwitchTips: Boolean
}

/** Presentation style for the composition, independent of any Android types. */
enum class InlinePreeditStyle {
    DISABLE,
    COMPOSING_TEXT,
    COMMIT_TEXT_PREVIEW,
}
