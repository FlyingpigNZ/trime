/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.disambiguation

/**
 * The owned input state of the pinyin disambiguation panel — the typed key code
 * plus the syllables the user confirmed from the panel, and nothing else.
 *
 * Kept apart from [PinyinDisambiguationController] (which owns the Rime calls
 * and the panel view) so the whole state model is unit-testable: the controller
 * only drives it from key events, panel picks and the engine's raw input.
 *
 * ## Model
 *
 * [typedCode] is the code string the user typed character by character (T9
 * digits, or the 14-key representative letters / uppercase tokens). A panel
 * pick confirms one syllable at [confirmed] with the code Rime's dictionary is
 * indexed by, and [feed] is the composition that encodes the whole state:
 *
 *     confirmed codes, `'`-joined, + the still-unconfirmed typed tail
 *     e.g. `hao'de'33` (T9 digits), `vs'qd` (14-key letters)
 *
 * That string is exactly what a pick sends to Rime, which is why the engine's
 * raw input is the authority for [reconcile]: it is either [feed] (idle), a
 * shorter prefix of it (Escape / delete truncated the composition), or [feed]
 * with the characters the user kept typing appended (Rime appends to the raw
 * input; only a pick re-feeds it).
 *
 * @param isTypedCodeChar whether a character can be part of [typedCode] for the
 * active mode (digits for the T9 modes, the 14 representative letters or the 14
 * uppercase tokens for `flypy14`). Used by [reconcile] to tell a typed tail from
 * input the engine holds but we never typed.
 */
internal class PinyinDisambiguationState(
    private val isTypedCodeChar: (Char) -> Boolean,
) {
    /**
     * One confirmed syllable. [pinyin] is the *display* form (full pinyin, e.g.
     * `cha`); [code] is what is fed to Rime (the full pinyin for FULL, the
     * 小鹤双拼 code for FLYPY/FLYPY14, e.g. `ia`). The two differ only in flypy
     * mode: the panel shows the readable pinyin but Rime's dictionary is
     * indexed by the 双拼 code. [consumed] counts the typed code characters the
     * syllable consumed — it accumulates into the tail offset and lets a
     * reconcile drop exactly the typed characters a dropped syllable owned.
     *
     * An **opaque** entry records an external 词组 pick that went beyond the
     * panel-confirmed syllables: [pinyin] is empty and [code] is the typed
     * key-code run the pick consumed (its folded spelling). Backspace pops it
     * to roll the pick back ([undo] distinguishes it by `pinyin.isEmpty()`).
     */
    data class Confirmed(
        val pinyin: String,
        val code: String,
        val consumed: Int,
    )

    /** The typed key-code string the user has entered (digits for T9, letters for 14-key). */
    var typedCode: String = ""
        private set

    private val confirmedEntries = mutableListOf<Confirmed>()

    val confirmed: List<Confirmed> get() = confirmedEntries

    /** How many leading typed code characters the confirmed syllables have consumed. */
    val consumedTyped: Int get() = confirmedEntries.sumOf { it.consumed }

    /** The still-unconfirmed trailing typed code — what the panel decodes. */
    val pendingCode: String get() = typedCode.substring(minOf(consumedTyped, typedCode.length))

    val hasConfirmations: Boolean get() = confirmedEntries.isNotEmpty()

    /** True when there is nothing to decode and nothing to re-feed. */
    val isEmpty: Boolean get() = typedCode.isEmpty() && confirmedEntries.isEmpty()

    /**
     * The input string that encodes everything confirmed so far + the trailing
     * un-confirmed typed code: confirmed codes `'`-joined, then the remaining
     * typed code. Form: `hao'de'33` (T9 digits) / `vs'qd` (14-key letters).
     * With no confirmed entries the pure typed code is returned (stay on the
     * fold path), and with nothing left un-confirmed no dangling separator is
     * emitted (librime would wait for another syllable and the terminal
     * composition would corrupt, breaking Backspace after a full confirm).
     */
    val feed: String
        get() {
            val codePart = confirmedEntries.joinToString(SEPARATOR.toString()) { it.code }
            val tail = pendingCode
            return when {
                codePart.isEmpty() -> typedCode
                tail.isEmpty() -> codePart
                else -> codePart + SEPARATOR + tail
            }
        }

    fun clear() {
        typedCode = ""
        confirmedEntries.clear()
    }

    /** A typed code character reached Rime (the key is never consumed by us). */
    fun appendTyped(c: Char) {
        typedCode += c
    }

    /** A plain delete of one (still un-confirmed) typed code character. */
    fun dropTrailingTyped() {
        typedCode = typedCode.dropLast(1)
    }

    /** Record a panel pick. */
    fun confirm(
        pinyin: String,
        code: String,
        consumed: Int,
    ) {
        confirmedEntries += Confirmed(pinyin, code, consumed)
    }

    /** Pop the last confirmation; null when nothing was confirmed. */
    fun undo(): Confirmed? = confirmedEntries.removeLastOrNull()

    /**
     * Fold an external pick into the state: the engine reports [tailLength]
     * typed code characters still un-confirmed, so everything before them was
     * consumed by the pick. Recorded as one opaque entry whose code is the typed
     * run itself (the folded spelling the engine matched), so re-feeding it
     * verbatim keeps the composition narrowed.
     *
     * Returns true when the state advanced.
     */
    fun absorbConsumedPrefix(tailLength: Int): Boolean {
        val newConsumed = typedCode.length - tailLength
        if (newConsumed <= consumedTyped) return false
        val gap = typedCode.substring(consumedTyped, newConsumed)
        if (gap.isEmpty()) return false
        confirmedEntries += Confirmed(pinyin = "", code = gap, consumed = gap.length)
        return true
    }

    /**
     * Re-anchor the state to [raw], the engine's raw input.
     *
     * Owned state is the *feeding* model, so in the steady state [raw] is [feed]
     * plus whatever the user typed since the last pick (Rime appends typed
     * characters to the raw input). It can also be **shorter**, and nothing else
     * in the controller observes that:
     *
     *  - **Escape** (librime `Editor::CancelComposition` →
     *    `Context::ClearPreviousSegment`) truncates the raw input at the last
     *    segment instead of clearing the composition, so no
     *    `onCompositionUpdate(length <= 0)` reset happens. The typed code
     *    shrank while [confirmed] kept the syllables the engine no longer
     *    holds, and the next pick re-fed them — after Escape then `vz` the
     *    panel fed `zhou'mo'zhou` instead of `zhou`. (Reported 2026-11-13:
     *    `llgevzmo` → 两个 + panel `zhou`/`mo` → Escape → `vz` → stale
     *    `zhou`/`mo` came back.)
     *  - an editor delete key / a caret edit moves the cut anywhere else.
     *
     * So the engine's input wins: keep the longest prefix of [confirmed] whose
     * code spelling still sits at the head of [raw] (dropping the typed
     * characters those syllables consumed), then match the engine's remaining
     * tail against the typed tail. A tail the engine holds but we never recorded
     * is adopted when every extra character is one we could have typed (the cut
     * landed inside a code spelling); anything else means the input is no longer
     * our model of it, and the state is dropped instead of re-feeding stale
     * syllables — the panel restarts from the next typed key.
     */
    fun reconcile(raw: String) {
        if (isEmpty) return
        var offset = 0
        var keptTyped = 0
        var kept = 0
        while (kept < confirmedEntries.size) {
            val entry = confirmedEntries[kept]
            val end = offset + entry.code.length
            val codePresent = raw.length >= end && raw.regionMatches(offset, entry.code, 0, entry.code.length)
            if (!codePresent) break
            // A code is only intact when the engine's separator follows it (or
            // it ends the input); otherwise the cut landed inside the code.
            if (raw.length > end && raw[end] != SEPARATOR) break
            keptTyped += entry.consumed
            offset = if (raw.length > end) end + 1 else end
            kept++
        }
        if (kept < confirmedEntries.size) {
            // The engine no longer holds these syllables: drop them together
            // with the typed characters they consumed.
            while (confirmedEntries.size > kept) confirmedEntries.removeAt(confirmedEntries.size - 1)
            typedCode = typedCode.take(minOf(keptTyped, typedCode.length))
        }
        val engineTail = raw.substring(minOf(offset, raw.length))
        val typedTail = pendingCode
        when {
            // The engine's tail is exactly what we typed, or a truncated
            // version of it (Escape / delete inside the un-confirmed code).
            typedTail.startsWith(engineTail) -> typedCode = typedCode.take(consumedTyped + engineTail.length)
            // The engine holds characters we never recorded (a key raced our
            // own re-feed, or the cut exposed part of a code spelling): adopt
            // them when the user could have typed them.
            engineTail.startsWith(typedTail) && engineTail.drop(typedTail.length).all(isTypedCodeChar) ->
                typedCode += engineTail.drop(typedTail.length)
            else -> clear()
        }
    }

    companion object {
        /** Separator between fed syllables (librime's syllable delimiter). */
        const val SEPARATOR = '\''
    }
}
