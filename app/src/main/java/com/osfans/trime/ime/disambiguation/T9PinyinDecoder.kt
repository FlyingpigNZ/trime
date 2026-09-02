/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.disambiguation

import com.osfans.trime.data.theme.SchemaExtension

/**
 * Decodes a T9 digit string into the set of legal pinyin sequences.
 *
 * Feature ②: the user types digits on a T9 keyboard (full-pinyin T9 or
 * 小鹤双拼 T9); this decoder lists every legal pinyin parse of the digit
 * string so the app can show them in the disambiguation column and feed the
 * chosen pinyin back to Rime.
 *
 * Two paths:
 * - **full** (`wanxiang_t9`): the digit string is a full-pinyin string folded
 *   to digits. Each syllable's `t9_code` must concatenate to the input.
 * - **flypy** (`wanxiang_flypy_t9`): the digit string is a 小鹤双拼 code
 *   folded to digits, two digits per syllable. Each pair decodes via the
 *   双拼 key mapping to a full pinyin syllable.
 */
class T9PinyinDecoder(
    private val extension: SchemaExtension.T9Disambiguation,
) {
    private val syllablesByT9: Map<String, List<SchemaExtension.T9Disambiguation.Syllable>> =
        extension.syllables.groupBy { it.t9Code }

    private val syllablesByFlypyT9: Map<String, List<SchemaExtension.T9Disambiguation.Syllable>> =
        extension.syllables.groupBy { it.flypyT9Code }

    /**
     * Decode [digits] into legal pinyin sequences. Returns an empty list when
     * the input is empty, the extension is disabled, or nothing decodes.
     */
    fun decode(digits: String): List<List<String>> {
        if (digits.isEmpty()) return emptyList()
        return when (extension.inputMethod) {
            SchemaExtension.T9Disambiguation.InputMethod.FULL -> decodeFull(digits)
            SchemaExtension.T9Disambiguation.InputMethod.FLYPY -> decodeFlypy(digits)
        }
    }

    /** All legal pinyin sequences joined into display strings (e.g. `hao`, `ni hao`). */
    fun decodeToDisplay(digits: String): List<String> = decode(digits)
        .map { it.joinToString(" ") }

    /**
     * Enumerate every pinyin candidate that can **start** [digits], together
     * with how many leading digit characters each consumes.
     *
     * Feature ② shows this list in the panel and, on a pick, advances the
     * panel by the chosen candidate's [Segment.consumed], then re-decodes the
     * remainder — so the list always reflects the *current* un-consumed digits.
     *
     * Candidates are the union of:
     *  - single leading **initial** letters on the first digit (a pinyin
     *    syllable never starts with an invalid initial, so the letter→digit
     *    map derived from the syllable table is the source of truth — e.g. `4`
     *    yields `g`,`h` but not `i`);
     *  - every **complete syllable** matched by a digit prefix of any length
     *    starting at position 0 (e.g. `426` yields `gan`/`gao`/`han`/`hao`).
     *
     * This matches the user model: "input `42633` → show `g, h, ga, ha, gao,
     * hao, …`; pick one → leftover `2633`/`633`/`33` → show the same for that
     * remainder; continue until the whole string is consumed, then feed the
     * assembled pinyin to Rime."
     *
     * Returns an empty [Segment.pinyin] when [digits] is empty or the first
     * digit cannot start any syllable (nothing to disambiguate).
     */
    fun decodeLeading(digits: String): List<Segment> {
        if (digits.isEmpty()) return emptyList()
        // pinyin -> (code, shortest consumed). `code` is what must be fed to
        // Rime: the full pinyin for FULL, the 小鹤双拼 code (e.g. `cha`→`ia`)
        // for FLYPY.
        val merged = LinkedHashMap<String, Pair<String, Int>>()
        // 1) single initials on the first digit (in the order of the key pad).
        // Only for FULL pinyin: a bare initial like `g`/`h` is a legal *full*
        // pinyin surrogate the panel may offer, but in 小鹤双拼 each syllable is
        // exactly two digits and a lone letter is NOT a valid 双拼 code, so it
        // would be fed to Rime as an invalid code. Skip initials for FLYPY.
        if (extension.inputMethod == SchemaExtension.T9Disambiguation.InputMethod.FULL) {
            validStartLettersOn(digits[0]).forEach { addCandidate(merged, digits, it, it, 1) }
        }
        // 2) complete syllables for every prefix length starting at 0.
        for (len in 1..digits.length) {
            matchesFor(digits.substring(0, len)).orEmpty().forEach { (pinyin, code) ->
                addCandidate(merged, digits, pinyin, code, len)
            }
        }
        // Keep the shortest consumed length for each distinct pinyin, then
        // present them longest-first (the syllable consuming the most leading
        // digits first, e.g. `hao`/`gao` before `ga`/`ha` and the bare initials
        // `g`/`h`), so the panel lists the "complete syllable" candidates before
        // the partial/initial ones.
        return merged.entries
            .map { (pinyin, codeAndConsumed) ->
                Segment(codeAndConsumed.second, listOf(pinyin), codeAndConsumed.first)
            }
            .sortedWith(compareByDescending<Segment> { it.consumed }.thenBy { it.pinyin[0] })
    }

    private fun addCandidate(
        merged: MutableMap<String, Pair<String, Int>>,
        digits: String,
        pinyin: String,
        code: String,
        consumed: Int,
    ) {
        if (consumed !in 1..digits.length) return
        val existing = merged[pinyin]
        if (existing == null || consumed < existing.second) merged[pinyin] = code to consumed
    }

    /** The single leading-letter initials that can appear on [digit]. */
    private fun validStartLettersOn(digit: Char): List<String> = startLettersByDigit[digit].orEmpty()

    /** Complete-syllable matches for a digit prefix: (pinyin, rime code). */
    private fun matchesFor(code: String): List<Pair<String, String>>? = when (extension.inputMethod) {
        SchemaExtension.T9Disambiguation.InputMethod.FULL -> syllablesByT9[code]?.map { it.pinyin to it.pinyin }
        SchemaExtension.T9Disambiguation.InputMethod.FLYPY -> syllablesByFlypyT9[code]?.map { it.pinyin to it.flypyCode }
    }

    /** digit → the pinyin-start letters (initials) that the T9 key carries. */
    private val startLettersByDigit: Map<Char, List<String>> by lazy {
        val fromSyllables = mutableMapOf<Char, MutableSet<Char>>()
        extension.syllables.forEach { s ->
            val first = s.pinyin.firstOrNull() ?: return@forEach
            val digit = s.t9Code.firstOrNull() ?: return@forEach
            fromSyllables.getOrPut(digit) { linkedSetOf() }.add(first)
        }
        // Letters that never start a syllable (i, u, v) simply don't appear in
        // fromSyllables, so they are never offered as a single initial.
        fromSyllables.mapValues { (_, letters) -> letters.map { it.toString() }.sorted() }
    }

    /** The leading candidates of a digit string: pinyin + digit length + code. */
    data class Segment(
        /** Number of leading digit characters this candidate consumes. */
        val consumed: Int,
        /** The candidate pinyin entry (a single pinyin string, e.g. `hao`). */
        val pinyin: List<String>,
        /**
         * The code to feed to Rime when this syllable is confirmed: the full
         * pinyin for FULL (e.g. `hao`), the 小鹤双拼 code for FLYPY (e.g. `cha`
         * → `ia`). `feedMixedComposition` feeds this, not [pinyin].
         */
        val code: String,
    )

    // ── full pinyin: DP over syllable t9 codes ────────────────────────────
    private fun decodeFull(digits: String): List<List<String>> {
        if (digits.isEmpty()) return emptyList()
        val n = digits.length
        // best[i] = list of syllable sequences covering digits[0 until i]
        val best = Array(n + 1) { mutableListOf<List<String>>() }
        best[0].add(emptyList())
        for (i in 1..n) {
            for (j in 0 until i) {
                if (best[j].isEmpty()) continue
                val code = digits.substring(j, i)
                val matches = syllablesByT9[code] ?: continue
                for (syllable in matches) {
                    for (prefix in best[j]) {
                        best[i].add(prefix + syllable.pinyin)
                    }
                }
            }
        }
        // dedupe (a digit string may reach the same sequence via different splits)
        return best[n].distinct()
    }

    // ── flypy: two digits per syllable ────────────────────────────────────
    private fun decodeFlypy(digits: String): List<List<String>> {
        if (digits.length % 2 != 0) return emptyList()
        val result = mutableListOf<List<String>>()
        fun dfs(
            start: Int,
            acc: List<String>,
        ) {
            if (start == digits.length) {
                result.add(acc)
                return
            }
            val code = digits.substring(start, start + 2)
            val syllables = syllablesByFlypyT9[code] ?: return
            for (syllable in syllables) {
                dfs(start + 2, acc + syllable.pinyin)
            }
        }
        dfs(0, emptyList())
        return result.distinct()
    }
}
