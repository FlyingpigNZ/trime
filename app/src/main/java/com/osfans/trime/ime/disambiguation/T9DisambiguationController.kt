/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.disambiguation

import android.view.View
import android.widget.FrameLayout
import androidx.core.view.children
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.SchemaExtension
import com.osfans.trime.data.theme.SchemaExtensionResolver
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardView
import timber.log.Timber

/**
 * Owns the T9 pinyin disambiguation panel (feature ②).
 *
 * Model (validated on-device): the T9 digit fold is lossy (`426` maps to
 * `gan`/`gao`/`han`/`hao`), so Rime produces the union 汉字 for all of them.
 * This controller owns the *input*: it keeps the full digit string the user
 * typed ([lastDigits]) and the list of pinyin syllables the user confirmed
 * from the panel ([confirmed]). On a pick or an undo it assembles a **mixed
 * composition** and feeds it to Rime via `setInput`:
 *
 *     confirmed pinyin, `'`-joined, + remaining un-confirmed digits
 *     e.g. `hao'de'33`
 *
 * Because `setInput` sets the context input directly and the dictionary is
 * indexed by BOTH the pinyin code and the `/9jian`-folded digit code, Rime
 * resolves the pinyin segment (`hao'de`) by pinyin and the digit segment
 * (`33`) by the folded index in ONE schema — no filter needed.
 *
 * The input is driven from the key layer ([onDigitKey] / [onBackspace]), not
 * from Rime composition diffs: once a pinyin is confirmed, Rime's raw input
 * is `hao'…` (not pure digits), so the controller owns the digit string and
 * does not try to reverse-engineer it from Rime.
 *
 * The panel only appears while the active keyboard is the T9 keyboard whose
 * first column is the punctuation column it overlays.
 */
class T9DisambiguationController(
    private val rime: RimeSession,
    private val theme: Theme,
) : InputBroadcastReceiver {

    private var panel: T9DisambiguationPanel? = null
    private var decoder: T9PinyinDecoder? = null
    private var extension: SchemaExtension.T9Disambiguation? = null
    private var keyboard: Keyboard? = null
    private var keyboardView: KeyboardView? = null
    private var currentSchemaId: String = ""
    private var currentKeyboardId: String = ""

    /** Whether the panel is currently attached to the keyboard view. */
    val isAttached: Boolean
        get() = panel != null

    /** The full digit string the user has typed (owned by this controller). */
    private var lastDigits: String = ""

    /**
     * A confirmed pinyin syllable. [pinyin] is the *display* form (full pinyin,
     * e.g. `cha`); [code] is what is fed to Rime (the full pinyin for FULL, the
     * 小鹤双拼 code for FLYPY, e.g. `ia`). The two differ only in flypy mode:
     * the panel shows the readable pinyin but Rime's dictionary is indexed by
     * the 双拼 code. `consumed` accumulates into the panel's leading offset.
     */
    private data class Confirmed(val pinyin: String, val code: String, val consumed: Int)

    private val confirmed = mutableListOf<Confirmed>()

    /** Leading candidates currently displayed (a segment per pinyin option). */
    private var leadingSegments: List<T9PinyinDecoder.Segment> = emptyList()

    /** How many leading digits the confirmed syllables have consumed. */
    private val consumedDigits: Int get() = confirmed.sumOf { it.consumed }

    /** Attach the panel to [keyboardView] (a FrameLayout that hosts the keyboard). */
    fun attach(
        keyboardView: FrameLayout,
        keyboard: Keyboard,
        keyboardId: String,
    ) {
        this.keyboard = keyboard
        this.currentKeyboardId = keyboardId
        // The host FrameLayout holds the KeyboardView (the draw surface) and,
        // after this method, the panel. Find the KeyboardView so the controller
        // can invalidate first-column keys when the panel overlay toggles.
        this.keyboardView = keyboardView.children.firstOrNull { it is KeyboardView } as? KeyboardView
        panel?.let { keyboardView.removeView(it) }
        val created =
            T9DisambiguationPanel(
                keyboardView.context,
                theme,
                keyboard,
                onPick = ::onPick,
            ).apply {
                visibility = View.GONE
                keyboardView.addView(
                    this,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        panel = created
        Timber.d("t9diag: attach done, schema=${rime.uiState.value.schemaId} eligible=${isEligibleKeyboard()} enabled=${extension?.enabled}")
        val schemaId = rime.uiState.value.schemaId
        if (schemaId.isNotEmpty() && schemaId != currentSchemaId) {
            currentSchemaId = schemaId
            reloadForSchema(schemaId)
        }
        // The keyboard window (and this panel) is detached while the unrolled
        // candidate window replaces it, so composition updates that happen
        // there (e.g. a 词组 pick) never reach onCompositionUpdate. On
        // re-attach, reconcile the owned state from the engine's latest
        // composition before drawing, exactly like onCompositionUpdate would.
        val current = rime.uiState.value.composition
        if (current.length <= 0) {
            resetState()
        } else {
            collapseToEngineRemaining(current)
        }
        refreshPanel()
    }

    fun detach() {
        Timber.d("t9diag: detach, panel=${panel != null}")
        keyboard?.pinyinOverlayVisible = false
        panel?.let { (it.parent as? FrameLayout)?.removeView(it) }
        panel = null
        keyboard = null
        keyboardView = null
        currentKeyboardId = ""
        leadingSegments = emptyList()
        // Schema binding (extension/decoder/currentSchemaId) and the owned input
        // state (lastDigits/confirmed) are deliberately preserved: a window swap
        // (e.g. the unrolled candidate window replacing the keyboard window) does
        // NOT commit Rime's composition, so re-attaching must keep decoding the
        // same digit string instead of starting over and desyncing from Rime.
        // A real commit clears them via onCompositionUpdate(length <= 0), and a
        // schema switch clears them via reloadForSchema.
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        if (schema.id == currentSchemaId) {
            // The engine was restarted for the same schema (e.g. a package
            // re-import/re-activation redeploys the active schema): the
            // extension file on disk may have changed, so reload
            // unconditionally instead of skipping. A restart clears the Rime
            // composition, so dropping the owned digit/confirmed state here is
            // always consistent.
            reloadForSchema(schema.id)
            return
        }
        currentSchemaId = schema.id
        reloadForSchema(schema.id)
    }

    private fun reloadForSchema(schemaId: String) {
        val ext = SchemaExtensionResolver.loadForSchema(schemaId)
        extension = ext?.t9Disambiguation
        Timber.d("t9diag: reloadForSchema '$schemaId' ext=${ext != null} t9=${ext?.t9Disambiguation != null} enabled=${ext?.t9Disambiguation?.enabled}")
        decoder = extension?.let { T9PinyinDecoder(it) }
        lastDigits = ""
        confirmed.clear()
        leadingSegments = emptyList()
        refreshPanel()
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        // Keep the panel driven by owned state, not by raw diffs. The one thing
        // Rime tells us that matters is an empty length (composition cleared,
        // e.g. a commit or a schema reset): drop all pending disambiguation.
        if (data.length <= 0) {
            resetState()
        } else {
            // External commits (picking a 词组 candidate from the Rime candidate
            // window) do NOT shrink the engine raw input (librime Context::Select
            // only converts the segment); the already-consumed prefix disappears
            // from the composition's preedit instead. Re-anchor the owned digit
            // string from that preedit when possible.
            collapseToEngineRemaining(data)
        }
        refreshPanel()
    }

    /**
     * After a candidate was picked from the Rime candidate window, the engine
     * raw input still holds every typed digit, but the composition's preedit
     * only spans the still-uncommitted tail — librime renders the converted
     * prefix as its candidate text, then starts the [CompositionProto.selStart,
     * selEnd] range at the remaining segment. Fold that tail's pinyin back to
     * digits with our own syllable table (t9_code for FULL, flypy_t9_code for
     * FLYPY); when the result is a strict suffix of the digits we still own,
     * the prefix was consumed by the external pick — drop it so the panel
     * resumes at the first syllable of the remainder.
     *
     * Every step is guarded: anything that does not parse cleanly or does not
     * match the owned tail leaves the state untouched.
     */
    private fun collapseToEngineRemaining(data: CompositionProto) {
        if (!isActive() || confirmed.isNotEmpty()) return
        val ext = extension ?: return
        val preedit = data.preedit ?: return
        val selStart = data.selStart
        val selEnd = data.selEnd
        if (selStart <= 0 || selEnd <= selStart || selEnd > preedit.length) return
        val tail = preedit.substring(selStart, selEnd)
        val syllables = ext.syllables
        val pinyinToCode: (String) -> String? =
            when (ext.inputMethod) {
                SchemaExtension.T9Disambiguation.InputMethod.FULL ->
                    { p: String -> syllables.firstOrNull { it.pinyin == p }?.t9Code }
                SchemaExtension.T9Disambiguation.InputMethod.FLYPY ->
                    { p: String -> syllables.firstOrNull { it.pinyin == p }?.flypyT9Code }
            }
        val pinyinSet = syllables.map { it.pinyin }.toSet()
        val suffixDigits = pinyinTailToDigits(tail, pinyinSet, pinyinToCode) ?: run {
            Timber.d("t9diag: preedit tail '$tail' did not parse to syllables; not collapsing")
            return
        }
        if (suffixDigits.isEmpty()) return
        if (!lastDigits.endsWith(suffixDigits)) {
            Timber.d("t9diag: folded tail '$tail' -> '$suffixDigits' is not a suffix of owned '$lastDigits'; not collapsing")
            return
        }
        if (suffixDigits.length >= lastDigits.length) return
        Timber.d("t9diag: external pick collapsed owned '$lastDigits' -> tail '$suffixDigits' (preedit '$preedit' sel $selStart..$selEnd)")
        lastDigits = suffixDigits
        confirmed.clear()
        leadingSegments = emptyList()
    }

    /**
     * Split the pinyin display of the remaining segment into its syllable
     * codes. Handles both space-separated pinyin (`yang ren`) and runs without
     * separators (segmented against the syllable table), after stripping tone
     * marks. Returns null when any part fails to parse.
     */
    private fun pinyinTailToDigits(
        text: String,
        pinyinSet: Set<String>,
        codeFor: (String) -> String?,
    ): String? {
        val plain = stripToneMarks(text)
        val spaceTokens = plain.split(' ').filter { it.isNotEmpty() }
        val tokens =
            if (spaceTokens.isNotEmpty() && spaceTokens.all { it in pinyinSet }) {
                spaceTokens
            } else {
                segmentPinyin(plain, pinyinSet) ?: return null
            }
        val result = StringBuilder()
        for (token in tokens) {
            val code = codeFor(token) ?: return null
            result.append(code)
        }
        return result.toString()
    }

    /** Strip pinyin tone marks, keeping plain letters/ü (as v). */
    private fun stripToneMarks(text: String): String {
        val plain = StringBuilder()
        for (ch in text) {
            plain.append(
                when (ch) {
                    'ā', 'á', 'ǎ', 'à' -> 'a'
                    'ē', 'é', 'ě', 'è' -> 'e'
                    'ī', 'í', 'ǐ', 'ì' -> 'i'
                    'ō', 'ó', 'ǒ', 'ò' -> 'o'
                    'ū', 'ú', 'ǔ', 'ù' -> 'u'
                    'ǖ', 'ǘ', 'ǚ', 'ǜ', 'ü' -> 'v'
                    'ń', 'ň', 'ǹ' -> 'n'
                    'ḿ' -> 'm'
                    in 'a'..'z', ' ' -> ch
                    else -> continue
                },
            )
        }
        return plain.toString()
    }

    /** Greedy-segment a separator-free pinyin run against the syllable table. */
    private fun segmentPinyin(
        text: String,
        pinyinSet: Set<String>,
    ): List<String>? {
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var matched: String? = null
            for (len in 6 downTo 1) {
                if (i + len > text.length) continue
                val cand = text.substring(i, i + len)
                if (cand in pinyinSet) {
                    matched = cand
                    break
                }
            }
            if (matched == null) return null
            result.add(matched)
            i += matched.length
        }
        return result
    }

    /**
     * A T9 digit key was pressed by the user (T9 keyboard). Append it to the
     * owned digit string so the panel can keep decoding once a pinyin is
     * confirmed (Rime's raw input is `hao'…` then, not pure digits). The key is
     * NOT consumed: it still goes to Rime through the normal fold path, so the
     * un-confirmed trailing digits stay as T9 numbers. Returns false always.
     */
    fun onDigitKey(digit: Char): Boolean {
        if (!isActive()) return false
        lastDigits += digit
        refreshPanel()
        return false
    }

    /**
     * Backspace pressed. If a pinyin syllable is confirmed, undo the last
     * confirmed syllable (pop it, re-feed the shortened composition) and
     * consume the key — this is the "Backspace rolls back the last pinyin
     * selection" behavior. Otherwise it is a plain digit delete: drop the last
     * digit from our owned string and let Rime delete (not consumed).
     * Returns true when the controller consumed the key.
     */
    fun onBackspace(): Boolean {
        if (!isActive()) return false
        if (confirmed.isNotEmpty()) {
            val last = confirmed.removeAt(confirmed.size - 1)
            Timber.d("t9diag: undo pick '${last.pinyin}' (consumed=${last.consumed})")
            feedMixedComposition()
            refreshPanel()
            return true
        }
        // Plain digit delete in the all-number composition. We drop the last
        // digit from our owned string so the panel stays in sync, but do NOT
        // consume — Rime deletes it from its own composition too.
        lastDigits = lastDigits.dropLast(1)
        refreshPanel()
        return false
    }

    private fun resetState() {
        lastDigits = ""
        confirmed.clear()
        leadingSegments = emptyList()
    }

    /** Whether the controller is active: schema extension enabled + declared keyboard. */
    private fun isActive(): Boolean {
        val extension = extension ?: return false
        return extension.enabled && isEligibleKeyboard()
    }

    private fun refreshPanel() {
        val panel = panel ?: return
        val extension = extension ?: run {
            Timber.d("t9diag: refreshPanel no extension")
            return
        }
        val decoder = decoder ?: run {
            hidePanel(panel)
            Timber.d("t9diag: refreshPanel no decoder")
            return
        }
        if (!extension.enabled || !isEligibleKeyboard()) {
            Timber.d("t9diag: refreshPanel hidden, enabled=${extension.enabled} eligible=${isEligibleKeyboard()}")
            hidePanel(panel)
            return
        }
        val digits = lastDigits.substring(minOf(consumedDigits, lastDigits.length))
        val segments = decoder.decodeLeading(digits)
        leadingSegments = segments
        Timber.d("t9diag: refreshPanel raw='$lastDigits' consumed=$consumedDigits digits='$digits' segments=${segments.map { it.pinyin[0] to it.consumed }}")
        if (segments.isEmpty()) {
            hidePanel(panel)
            return
        }
        showPanel(panel, segments.map { it.pinyin[0] })
    }

    /** Show the panel + suppress the keyboard's first-column labels/symbols. */
    private fun showPanel(
        panel: T9DisambiguationPanel,
        sequences: List<String>,
    ) {
        keyboard?.pinyinOverlayVisible = true
        panel.submitSequences(sequences)
        keyboardView?.invalidateAllKeys()
    }

    /** Hide the panel + restore the keyboard's first-column labels/symbols. */
    private fun hidePanel(panel: T9DisambiguationPanel) {
        keyboard?.pinyinOverlayVisible = false
        panel.hide()
        keyboardView?.invalidateAllKeys()
    }

    /**
     * Assemble and feed the mixed composition to Rime so the candidate window
     * resolves the confirmed pinyin segment (via pinyin index) and the
     * remaining digit segment (via the folded index), in ONE schema.
     *
     * Form: `hao'de'33` — confirmed syllables `'`-joined, then the remaining
     * un-confirmed digits. No confirmed syllables ⇒ nothing to feed (stay on
     * the pure-digit fold path).
     */
    private fun feedMixedComposition() {
        // Join the Rime-facing *codes* (full pinyin for FULL, 小鹤双拼 code for
        // FLYPY) — this is what Rime's dictionary is indexed by, not the display
        // pinyin. For FLYPY `cha` must feed as `ia`, or Rime splits it into the
        // stray `ch`+`a` and mis-resolves the candidates.
        val codePart = confirmed.joinToString("'") { it.code }
        val digitsPart = lastDigits.substring(minOf(consumedDigits, lastDigits.length))
        val feed =
            if (codePart.isEmpty()) {
                // No confirmed syllable: restore the pure-digit composition so
                // Rime re-translates the T9 fold path (undo of the whole pick).
                lastDigits
            } else {
                buildString {
                    append(codePart)
                    append('\'')
                    append(digitsPart)
                }
            }
        Timber.d("t9diag: feed mixed composition '$feed' (display='${confirmed.joinToString("'") { it.pinyin }}')")
        if (feed.isNotEmpty()) {
            rime.launchOnReady { it.setInput(feed) }
        }
    }

    /**
     * Whether the panel may overlay the current keyboard. The schema extension
     * can declare the keyboard it overlays (`t9_disambiguation.keyboard`); when
     * it does, only that keyboard is eligible. When it is left unset, the panel
     * applies to any keyboard the schema is on (the schema author takes
     * responsibility for the overlay). T9-ness itself is decided by the schema
     * extension (`t9_disambiguation.enabled`), never by inspecting the keyboard
     * layout — that heuristic mis-fired on non-T9 keyboards.
     */
    private fun isEligibleKeyboard(): Boolean = isEligibleKeyboard(extension?.keyboard, currentKeyboardId)

    /**
     * Pure predicate: the current keyboard id matches the declared overlay
     * keyboard. A null/empty [declaredKeyboard] means "no constraint" (the
     * schema author takes responsibility), so any keyboard is eligible.
     *
     * @param declaredKeyboard the `t9_disambiguation.keyboard` value (or null)
     * @param currentKeyboardId the id of the active keyboard
     */
    companion object {
        fun isEligibleKeyboard(
            declaredKeyboard: String?,
            currentKeyboardId: String,
        ): Boolean = declaredKeyboard.isNullOrEmpty() || declaredKeyboard == currentKeyboardId
    }

    /**
     * A pinyin was picked from the panel. Record the syllable + its consumed
     * digit count, feed the mixed composition so Rime narrows the candidate
     * window to the chosen pinyin, and re-decode the remainder for the panel.
     *
     * Each pick is an independent confirmation (no merging): picking `hao` then
     * `de` yields the pinyin part `hao'de`.
     */
    private fun onPick(pinyin: String) {
        Timber.d("T9 disambiguation pick: $pinyin")
        val segment = leadingSegments.firstOrNull { it.pinyin[0] == pinyin } ?: return
        confirmed.add(Confirmed(segment.pinyin[0], segment.code, segment.consumed))
        Timber.d("t9diag: confirmed='${confirmed.joinToString("'") { it.code }}' display='${confirmed.joinToString("'") { it.pinyin }}' consumed=$consumedDigits")
        feedMixedComposition()
        refreshPanel()
    }
}
