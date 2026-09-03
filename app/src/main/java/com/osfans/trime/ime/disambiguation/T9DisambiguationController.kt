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
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     *
     * An **opaque** entry records an external 词组 pick that went beyond the
     * panel-confirmed syllables: [pinyin] is empty and [code] is the typed-digit
     * run the pick consumed (its folded spelling). Backspace pops it to roll the
     * pick back ([onBackspace] distinguishes it by `pinyin.isEmpty()`).
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
            refreshPanel()
        } else {
            reconcileFromEngine()
        }
    }

    fun detach() {
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
            refreshPanel()
        } else {
            // External picks (a 词组 candidate from the Rime candidate window) do
            // NOT shrink the engine raw input (librime Context::Select only
            // converts a segment); librime re-anchors itself by marking the
            // consumed segments selected and opening a new trailing segment.
            // Ask the engine where that trailing segment actually starts and
            // re-anchor the owned state there.
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
     * /9jian fold, that trailing segment's raw is exactly the typed digits the
     * pick left behind (letters only ever appear in the already-consumed head
     * of a mixed feed), so the consumed span, in typed digits, is
     * `lastDigits.length - tail.length`. Raw-character arithmetic is not used:
     * a feed head mixes confirmed letter codes (`vi` = two letters plus one
     * delimiter) with digits, so raw characters do not map 1:1 to typed digits
     * and counting them re-counts previously consumed syllables (diag: with
     * `vi'2856…` already consumed as 治不了, raw-char pairing advanced the panel
     * past 洋人 digits that were never picked).
     *
     * This also covers the case the old code skipped entirely: a picked 词组
     * that covers syllables beyond the panel-confirmed ones ([confirmed]
     * non-empty), e.g. "confirm zhi from the panel, then pick 治不了".
     *
     * Every step is guarded: nothing changes unless the composition preedit
     * shows selected candidate text (non-ASCII) before the trailing segment,
     * the engine reports a pure-digit remainder that is a true suffix of the
     * owned digits, and the implied consumption is larger than what we already
     * track. The digits consumed beyond the panel-confirmed pinyin are
     * appended to [confirmed] as an opaque entry whose code is the typed-digit
     * run itself: it is the folded spelling the engine matched, so re-feeding
     * it verbatim keeps the composition narrowed, and Backspace pops it to
     * roll the pick back.
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
        if (!tail.all { it.isDigit() }) return
        // The trailing segment starts right after the consumed part, so the
        // consumed span in typed digits is lastDigits minus the tail. The tail
        // must be a true suffix of what the user typed: anything else means the
        // engine's remainder is not our digit string (e.g. a swallowed digit),
        // and advancing on it would mis-anchor the panel.
        val newConsumed = lastDigits.length - tail.length
        if (!lastDigits.endsWith(tail)) return
        if (newConsumed <= consumedDigits || newConsumed >= lastDigits.length) return
        val gapDigits = lastDigits.substring(consumedDigits, newConsumed)
        if (gapDigits.isNotEmpty()) {
            confirmed.add(Confirmed(pinyin = "", code = gapDigits, consumed = gapDigits.length))
        }
        leadingSegments = emptyList()
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
            val popped = confirmed.removeAt(confirmed.size - 1)
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
                rime.launchOnReady { api -> api.clearAndSetInput(buildFeed()) }
            } else {
                feedMixedComposition()
            }
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
        val extension = extension ?: return
        val decoder = decoder ?: run {
            hidePanel(panel)
            return
        }
        if (!extension.enabled || !isEligibleKeyboard()) {
            hidePanel(panel)
            return
        }
        val digits = lastDigits.substring(minOf(consumedDigits, lastDigits.length))
        val segments = decoder.decodeLeading(digits)
        leadingSegments = segments
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
     * The input string that encodes everything confirmed so far + the trailing
     * un-confirmed digits: confirmed codes `'`-joined, then the remaining typed
     * digits. Form: `hao'de'33` — confirmed syllables (full pinyin for FULL,
     * 小鹤双拼 code for FLYPY — the form Rime's dictionary is indexed by), then
     * the digit tail through the folded index. With no confirmed entries the
     * pure-digit string is returned (stay on the fold path).
     */
    private fun buildFeed(): String {
        val codePart = confirmed.joinToString("'") { it.code }
        val digitsPart = lastDigits.substring(minOf(consumedDigits, lastDigits.length))
        return if (codePart.isEmpty()) {
            lastDigits
        } else if (digitsPart.isEmpty()) {
            // Everything is confirmed: no trailing delimiter — a dangling "'"
            // would leave librime waiting for another syllable and can corrupt
            // the terminal composition (Backspace after full confirm broke).
            codePart
        } else {
            "$codePart'$digitsPart"
        }
    }

    private fun feedMixedComposition() {
        val feed = buildFeed()
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
        val segment = leadingSegments.firstOrNull { it.pinyin[0] == pinyin } ?: return
        confirmed.add(Confirmed(segment.pinyin[0], segment.code, segment.consumed))
        feedMixedComposition()
        refreshPanel()
    }
}
