/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.disambiguation

import android.view.View
import android.widget.FrameLayout
import androidx.core.view.children
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.SchemaExtension
import com.osfans.trime.data.theme.SchemaExtensionResolver
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the pinyin disambiguation panel (feature ②).
 *
 * One controller serves the two overlay schemes the extension can declare:
 * the **T9** keyboard flow (digit keys; the panel covers the first punctuation
 * column — [Keyboard.PinyinOverlay.FIRST_COLUMN]) and the **小鹤双拼14键** flow
 * (letter keys; the panel covers the first digit row —
 * [Keyboard.PinyinOverlay.FIRST_ROW]). Which flow applies is decided by the
 * schema extension's `input_method` (`full`/`flypy` vs `flypy14`), never by
 * inspecting the keyboard layout.
 *
 * The master on/off switch for both flows is the「键盘样式」settings toggle
 * `ThemeManager.prefs.pinyinFilter` (default on).
 *
 * Model (validated on-device): the key-code fold is lossy — T9 digits (`426`
 * maps to `gan`/`gao`/`han`/`hao`) and the 14-key letter fold (`ca` can be
 * 擦's 双拼 `ca` or 中's 双拼 `vs`) — so Rime produces the union 汉字 for all
 * of them. This controller owns the *input*: it keeps the full typed key-code
 * string the user entered (digits for T9, the 14 representative letters for
 * 14-key) and the list of pinyin syllables the user confirmed from the panel
 * ([PinyinDisambiguationState]). On a pick or an undo it assembles a **mixed
 * composition** and feeds it to Rime via `setInput`:
 *
 *     confirmed code, `'`-joined, + remaining un-confirmed typed code
 *     e.g. `hao'de'33` (T9 digits), `vs'qd` (14-key letters)
 *
 * Because `setInput` sets the context input directly and the dictionary keeps
 * the pinyin/双拼 code spellings alongside their folded spellings (`/9jian`
 * digits, `/14jian` letters), Rime resolves the confirmed segment by its code
 * spelling and the trailing segment by the folded spelling in ONE schema — no
 * filter needed.
 *
 * The input is driven from the key layer ([onDigitKey] / [onKeyLetter] /
 * [onBackspace]), not from Rime composition diffs: once a pinyin is
 * confirmed, Rime's raw input is `hao'…` / `vs'…` (not the raw typed fold), so
 * the controller owns the typed code string and does not try to reverse-engineer
 * it from Rime.
 *
 * The one thing that IS taken from Rime is its raw input, which is re-anchored
 * on every composition update ([reconcileFromEngine] →
 * [PinyinDisambiguationState.reconcile]): the engine can shrink the input behind
 * our back — Escape (`Context::ClearPreviousSegment`) truncates it at the last
 * segment and keeps the composition non-empty — and a forward-only
 * interpretation of the composition left the confirmed syllables in the owned
 * list, so the next pick re-fed them (2026-11-13 report: type `llgevzmo`,
 * pick 两个, panel-pick `zhou`/`mo`, press Escape, type `vz`: the panel then fed
 * `zhou'mo'zhou`).
 *
 * The panel only appears while the extension is enabled for the active schema
 * and the current keyboard matches the declared overlay keyboard
 * (`t9_disambiguation.keyboard`); its geometry follows the extension's
 * `input_method` (first column for T9, first row for 小鹤14键).
 */
class PinyinDisambiguationController(
    private val rime: RimeSession,
    private val theme: Theme,
) : InputBroadcastReceiver {

    private var panel: PinyinDisambiguationPanel? = null
    private var decoder: PinyinDisambiguationDecoder? = null
    private var extension: SchemaExtension.T9Disambiguation? = null
    private var keyboard: Keyboard? = null
    private var keyboardView: KeyboardView? = null
    private var panelHost: FrameLayout? = null
    private var currentSchemaId: String = ""
    private var currentKeyboardId: String = ""

    /** The key strip the current extension's overlay covers (matches [panel]). */
    private var panelOverlay: Keyboard.PinyinOverlay = Keyboard.PinyinOverlay.FIRST_COLUMN

    /** Whether the panel is currently attached to the keyboard view. */
    val isAttached: Boolean
        get() = panel != null

    /**
     * The owned input state: the typed key-code string and the confirmed
     * syllables. See [PinyinDisambiguationState] for the model and for why the
     * engine's raw input (not our own bookkeeping) decides what is still typed.
     */
    private val state = PinyinDisambiguationState { isOwnedCodeChar(it) }

    /** Leading candidates currently displayed (a segment per pinyin option). */
    private var leadingSegments: List<PinyinDisambiguationDecoder.Segment> = emptyList()

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
        // can invalidate the covered keys when the panel overlay toggles.
        this.panelHost = keyboardView
        this.keyboardView = keyboardView.children.firstOrNull { it is KeyboardView } as? KeyboardView
        panel?.let { keyboardView.removeView(it) }
        panel = null
        // Resolve the schema extension before building the panel so its geometry
        // matches the overlay strip of the current input_method (first column
        // for T9, first row for flypy14).
        val schemaId = rime.uiState.value.schemaId
        if (schemaId.isNotEmpty() && schemaId != currentSchemaId) {
            currentSchemaId = schemaId
            reloadForSchema(schemaId)
        }
        panelOverlay = overlayFor(extension)
        panel = buildPanel()
        // The keyboard window (and this panel) is detached while the unrolled
        // candidate window replaces it, so composition updates that happen
        // there (e.g. a 词组 pick) never reach onCompositionUpdate. On
        // re-attach, reconcile the owned state from the engine's latest
        // composition before drawing, exactly like onCompositionUpdate would.
        val current = rime.uiState.value.composition
        if (current.length <= 0) {
            resetState()
            refreshPanel()
        } else {
            reconcileFromEngine()
        }
    }

    fun detach() {
        keyboard?.pinyinOverlay = Keyboard.PinyinOverlay.NONE
        panel?.let { (it.parent as? FrameLayout)?.removeView(it) }
        panel = null
        keyboard = null
        keyboardView = null
        panelHost = null
        currentKeyboardId = ""
        leadingSegments = emptyList()
        // Schema binding (extension/decoder/currentSchemaId) and the owned input
        // state are deliberately preserved: a window swap
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
        decoder = extension?.let { PinyinDisambiguationDecoder(it) }
        state.clear()
        leadingSegments = emptyList()
        // The panel geometry follows the extension's input_method (first column
        // for T9, first row for flypy14). A same-schema restart can change the
        // on-disk extension (e.g. full -> flypy14); rebuild the panel then,
        // because KeyboardWindow caches the current keyboard and does not re-run
        // attach() for it.
        val nextOverlay = overlayFor(extension)
        val rebuild = panel != null && panelOverlay != nextOverlay
        if (rebuild) {
            panel?.let { (it.parent as? FrameLayout)?.removeView(it) }
            panel = null
        }
        panelOverlay = nextOverlay
        if (rebuild) {
            panel = buildPanel()
        }
        refreshPanel()
    }

    /** Build and add the floating panel with the current [panelOverlay]; null when detached. */
    private fun buildPanel(): PinyinDisambiguationPanel? {
        val host = panelHost ?: return null
        val kb = keyboard ?: return null
        return PinyinDisambiguationPanel(
            host.context,
            theme,
            kb,
            overlay = panelOverlay,
            onPick = ::onPick,
        ).apply {
            visibility = View.GONE
            host.addView(
                this,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        // Keep the panel driven by owned state, not by raw diffs. The one thing
        // Rime tells us that matters is an empty length (composition cleared,
        // e.g. a commit or a schema reset): drop all pending disambiguation.
        if (data.length <= 0) {
            resetState()
            refreshPanel()
        } else {
            // External picks (a 词组 candidate from the Rime candidate window) do
            // NOT shrink the engine raw input (librime Context::Select only
            // converts a segment); librime re-anchors itself by marking the
            // consumed segments selected and opening a new trailing segment.
            // Ask the engine where that trailing segment actually starts and
            // re-anchor the owned state there. The engine's raw input also decides
            // what is still typed: it shrinks when Escape / an editor delete
            // truncates the composition (see PinyinDisambiguationState.reconcile).
            reconcileFromEngine()
        }
    }

    /**
     * Re-anchor the owned state to the engine's true remaining input.
     *
     * librime never shrinks the raw input after an external candidate pick
     * (Context::Select only converts a segment); instead the composition marks
     * the consumed leading segments selected and librime opens a new trailing
     * segment at the consumed offset. With the tone digits removed from the
     * /9jian fold, that trailing segment's raw is exactly the typed code
     * characters the pick left behind (digits for T9, 14-key representative
     * letters or uppercase tokens for flypy14), so the consumed span, in
     * owned code characters, is
     * `typedCode.length - tail.length`. Raw-character arithmetic is not used:
     * a feed head mixes confirmed letter codes (`vi` = two letters plus one
     * delimiter) with digits, so raw characters do not map 1:1 to typed digits
     * and counting them re-counts previously consumed syllables (diag: with
     * `vi'2856…` already consumed as 治不了, raw-char pairing advanced the panel
     * past 洋人 digits that were never picked).
     *
     * This also covers the case the old code skipped entirely: a picked 词组
     * that covers syllables beyond the panel-confirmed ones
     * ([PinyinDisambiguationState.hasConfirmations]), e.g. "confirm zhi from the
     * panel, then pick 治不了".
     *
     * Every step is guarded: nothing changes unless the composition preedit
     * shows selected candidate text (non-ASCII) before the trailing segment,
     * the engine reports a pure remainder of owned code characters that is a
     * true suffix of the owned code, and the implied consumption is larger
     * than what we already track. The code characters consumed beyond the
     * panel-confirmed pinyin are
     * appended to the owned state as an opaque entry whose code is the
     * typed-digit run itself: it is the folded spelling the engine matched, so
     * re-feeding it verbatim keeps the composition narrowed, and Backspace pops
     * it to roll the pick back.
     */
    private fun reconcileFromEngine() {
        if (!isActive()) return
        rime.launchOnReady { api ->
            // launchOnReady resumes on the daemon's background dispatcher, but
            // everything here mutates owned state and drives the panel view
            // (RecyclerView / keyboard overlay), so the whole reconcile must
            // run on the main thread. The RimeApi calls inside are suspend and
            // hop to the rime dispatcher on their own.
            withContext(Dispatchers.Main.immediate) {
                val composition = rime.uiState.value.composition
                val preedit = composition.preedit ?: ""
                if (composition.length <= 0) {
                    // A commit / schema reset cleared the composition: drop all
                    // pending disambiguation.
                    resetState()
                } else {
                    // The engine's raw input is the authority for what is still
                    // typed: it shrinks when Escape / an editor delete truncates
                    // the composition, and Rime appends typed characters to it
                    // without asking us to re-feed. Re-anchor first, then fold
                    // in an external pick (which does not shrink the input).
                    state.reconcile(api.getRawInput())
                    advanceOwnedFromEngineTail(api, composition, preedit)
                }
                refreshPanel()
            }
        }
    }

    private suspend fun advanceOwnedFromEngineTail(
        api: RimeApi,
        composition: CompositionProto,
        preedit: String,
    ) {
        // Only an external pick turns the preedit prefix into selected
        // candidate text. A pre-pick auto-segmentation renders raw digits
        // there instead; never advance on that.
        val selStart = composition.selStart.coerceIn(0, preedit.length)
        val selectedText = preedit.substring(0, selStart)
        if (selectedText.none { it.code > 127 }) return
        val tail = api.remainingInputTail()
        if (tail == null || tail.isEmpty()) return
        if (tail.any { !isOwnedCodeChar(it) }) return
        // The engine's remainder must be a true suffix of what the user typed:
        // anything else means it is not our code string (e.g. a swallowed
        // character), and advancing on it would mis-anchor the panel.
        if (!state.typedCode.endsWith(tail)) return
        if (state.absorbConsumedPrefix(tail.length)) {
            leadingSegments = emptyList()
        }
    }

    /**
     * A T9 digit key was pressed by the user (T9 keyboard). Append it to the
     * owned code string so the panel can keep decoding once a pinyin is
     * confirmed (Rime's raw input is `hao'…` then, not pure digits). The key is
     * NOT consumed: it still goes to Rime through the normal fold path, so the
     * un-confirmed trailing digits stay as T9 numbers. Returns false always.
     * Only the digit modes (full/flypy) observe digit keys; the 14-key mode
     * observes letter keys via [onKeyLetter].
     */
    fun onDigitKey(digit: Char): Boolean {
        if (!isActive() || !isT9Mode()) return false
        state.appendTyped(digit)
        refreshPanel()
        return false
    }

    /**
     * A 小鹤双拼14键 key was pressed (what the keyboard sends: the 14
     * representative letters on the classic layout, or the 14 uppercase ASCII
     * tokens QW->A ... M->N on the token 试点 layout). Mirror of [onDigitKey] for
     * the `flypy14` mode: append the typed character to the owned code string
     * so the panel can decode once the current syllable is complete, and let
     * the key through to Rime (the normal 14-key fold path). Returns false
     * always.
     */
    fun onKeyLetter(letter: Char): Boolean {
        if (!isActive() || !is14KeyMode()) return false
        if (letter !in FOURTEEN_KEY_LETTERS && letter !in FOURTEEN_KEY_TOKENS) return false
        state.appendTyped(letter)
        refreshPanel()
        return false
    }

    /**
     * Backspace pressed. If a pinyin syllable is confirmed, undo the last
     * confirmed syllable (pop it, re-feed the shortened composition) and
     * consume the key — this is the "Backspace rolls back the last pinyin
     * selection" behavior. Otherwise it is a plain code-character delete: drop
     * the last typed code character from our owned string and let Rime delete
     * (not consumed).
     * Returns true when the controller consumed the key.
     */
    fun onBackspace(): Boolean {
        if (!isActive()) return false
        val popped = state.undo()
        if (popped != null) {
            if (popped.pinyin.isEmpty()) {
                // Opaque entry (an external 词组 pick recorded as the typed-digit
                // run it consumed). Its code is pure digits, so popping it can
                // rebuild a feed that is byte-identical to the engine's current
                // raw input (external picks never rewrite the input, they only
                // mark segments selected). librime's Segmentation::Reset keeps
                // every segment whose end is not past the diff position, so an
                // identical re-feed leaves the already-selected 词组 segment
                // alive and reconcile would immediately re-advance — the undo
                // would be undone. clearAndSetInput aborts the composition
                // (no commit) and re-parses the pre-pick standard input from an
                // empty composition in one step: the fresh segments are
                // unselected, so reconcile no longer re-advances.
                rime.launchOnReady { api -> api.clearAndSetInput(state.feed) }
            } else {
                feedMixedComposition()
            }
            refreshPanel()
            return true
        }
        // Plain code-character delete in the all-code composition. We drop the
        // last character from our owned string so the panel stays in sync, but do
        // NOT consume — Rime deletes it from its own composition too.
        state.dropTrailingTyped()
        refreshPanel()
        return false
    }

    private fun resetState() {
        state.clear()
        leadingSegments = emptyList()
    }

    /** Whether the controller is active: master pref + schema extension enabled + declared keyboard. */
    private fun isActive(): Boolean {
        val extension = extension ?: return false
        if (!ThemeManager.prefs.pinyinFilter.getValue()) return false
        return extension.enabled && isEligibleKeyboard()
    }

    /** The T9 digit modes (full-pinyin / 小鹤双拼 T9). */
    private fun isT9Mode(): Boolean {
        val inputMethod = extension?.inputMethod
        return inputMethod == SchemaExtension.T9Disambiguation.InputMethod.FULL ||
            inputMethod == SchemaExtension.T9Disambiguation.InputMethod.FLYPY
    }

    /** The 小鹤双拼14键 letter mode. */
    private fun is14KeyMode(): Boolean = extension?.inputMethod == SchemaExtension.T9Disambiguation.InputMethod.FLYPY14

    /** Whether [c] is a character of the owned key code for the active mode. */
    private fun isOwnedCodeChar(c: Char): Boolean = if (isT9Mode()) {
        c.isDigit()
    } else {
        c in FOURTEEN_KEY_LETTERS || c in FOURTEEN_KEY_TOKENS
    }

    private fun refreshPanel() {
        val panel = panel ?: return
        val decoder = decoder ?: run {
            hidePanel(panel)
            return
        }
        if (!isActive()) {
            hidePanel(panel)
            return
        }
        val digits = state.pendingCode
        val segments = decoder.decodeLeading(digits)
        leadingSegments = segments
        if (segments.isEmpty()) {
            hidePanel(panel)
            return
        }
        showPanel(panel, segments.map { it.pinyin[0] })
    }

    /** Show the panel + suppress the covered key strip's labels/symbols. */
    private fun showPanel(
        panel: PinyinDisambiguationPanel,
        sequences: List<String>,
    ) {
        keyboard?.pinyinOverlay = panelOverlay
        panel.submitSequences(sequences)
        keyboardView?.invalidateAllKeys()
    }

    /** Hide the panel + restore the covered key strip's labels/symbols. */
    private fun hidePanel(panel: PinyinDisambiguationPanel) {
        keyboard?.pinyinOverlay = Keyboard.PinyinOverlay.NONE
        panel.hide()
        keyboardView?.invalidateAllKeys()
    }

    /**
     * The overlay strip an extension maps to: the T9 keyboards' first column
     * for the digit modes, the 14-key keyboards' first row for `flypy14`.
     */
    private fun overlayFor(extension: SchemaExtension.T9Disambiguation?): Keyboard.PinyinOverlay = if (extension?.inputMethod == SchemaExtension.T9Disambiguation.InputMethod.FLYPY14) {
        Keyboard.PinyinOverlay.FIRST_ROW
    } else {
        Keyboard.PinyinOverlay.FIRST_COLUMN
    }

    private fun feedMixedComposition() {
        val feed = state.feed
        if (feed.isNotEmpty()) {
            rime.launchOnReady { it.setInput(feed) }
        }
    }

    /**
     * Whether the panel may overlay the current keyboard. The schema extension
     * can declare the keyboard it overlays (`t9_disambiguation.keyboard`); when
     * it does, only that keyboard is eligible. When it is left unset, the panel
     * applies to any keyboard the schema is on (the schema author takes
     * responsibility for the overlay). Which overlay flow applies is decided by
     * the schema extension (`t9_disambiguation.input_method` + `enabled`),
     * never by inspecting the keyboard layout — that heuristic mis-fired on
     * non-T9 keyboards.
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
        /**
         * Letters the classic 小鹤双拼14键 keyboard sends — the `/14jian`
         * representatives (Q W→q, E R→e, T Y→t, U I→u, O P→o, A S→a, D F→d,
         * G H→g, J K→j, L→l, Z X→z, C V→c, B N→b, M→m).
         */
        const val FOURTEEN_KEY_LETTERS = "qetuoadgjlzcbm"

        /**
         * The 14 uppercase ASCII tokens the token-试点 14键 keyboard sends,
         * one per key and disjoint from a-z (Q W->A, E R->B, ... B N->M, M->N),
         * mirroring the Rime `/14jian-token` fold.
         */
        const val FOURTEEN_KEY_TOKENS = "ABCDEFGHIJKLMN"

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
        val segment = leadingSegments.firstOrNull { it.pinyin[0] == pinyin } ?: return
        state.confirm(segment.pinyin[0], segment.code, segment.consumed)
        feedMixedComposition()
        refreshPanel()
    }
}
