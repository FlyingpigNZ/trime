/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.boolean
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string
import java.io.File

/**
 * Per-schema extension file (`<schemaId>.extended.yaml`).
 *
 * A sibling of the Rime `.schema.yaml` that the **app** owns: it carries
 * application-level per-schema directives that must not go into the Rime
 * compiled file — the schema-level `tool_bar` override (feature ①) and the
 * T9 disambiguation configuration / pinyin data (feature ②). The Rime engine
 * never reads this file.
 *
 * The file lives in the package workspace (the `rime/` prefix is stripped at
 * install time, so a package's `rime/wanxiang_t9.extended.yaml` lands at
 * `workspace/wanxiang_t9.extended.yaml`).
 */
data class SchemaExtension(
    /** Declared `schema_id`; null when absent. Used for validation only. */
    val schemaId: String? = null,
    /** Per-schema `tool_bar` override; null when the section is absent. */
    val toolBar: ToolBar? = null,
    /** Raw `tool_bar` node (kept for node-level merge semantics). */
    val toolBarNode: Node.Mapping? = null,
    /** T9 disambiguation configuration; null when the section is absent. */
    val t9Disambiguation: T9Disambiguation? = null,
) {

    /** T9 pinyin disambiguation (feature ②). */
    data class T9Disambiguation(
        /** Master switch. The plan: default on for `t9=true` schemas. */
        val enabled: Boolean = true,
        /** Input-method type: `full` (full-pinyin T9) or `flypy` (小鹤双拼 T9). */
        val inputMethod: InputMethod = InputMethod.FULL,
        /**
         * The keyboard id this disambiguation column overlays, e.g. `t9`. When
         * declared, the panel is only shown while that keyboard is active; when
         * empty, it applies to whatever keyboard the schema is using (the
         * schema author takes responsibility for the overlay). Null vs empty
         * both mean "no declared keyboard".
         */
        val keyboard: String? = null,
        /** Pinyin syllable table; each entry carries its T9 codes. */
        val syllables: List<Syllable> = emptyList(),
        /** 双拼 key mapping; empty for full-pinyin. */
        val flypyKeys: FlypyKeys = FlypyKeys(),
    ) {
        enum class InputMethod {
            FULL,
            FLYPY,
        }

        /** One pinyin syllable (no tone) with its derived codes. */
        data class Syllable(
            /** Full pinyin, e.g. `hao`. */
            val pinyin: String,
            /** Full pinyin → T9 digits, e.g. `426`. */
            val t9Code: String,
            /** 小鹤双拼 key code, e.g. `hc`. */
            val flypyCode: String,
            /** 小鹤双拼 code folded to T9 digits, e.g. `42`. */
            val flypyT9Code: String,
        )

        /** 小鹤双拼 initial/final key mapping (方案特定数据). */
        data class FlypyKeys(
            /** initial → key, e.g. `ch → i`. */
            val initials: Map<String, String> = emptyMap(),
            /** final → key, e.g. `ao → c`. */
            val finals: Map<String, String> = emptyMap(),
        )

        companion object {
            fun decode(node: Node.Mapping?): T9Disambiguation? {
                if (node == null) return null
                val enabled = node["enabled"]?.boolean ?: true
                val inputMethod = when (val raw = node["input_method"]?.string) {
                    null, "full", "quanpin" -> InputMethod.FULL
                    "flypy", "xiaoe" -> InputMethod.FLYPY
                    else -> throw IllegalArgumentException(
                        "t9_disambiguation.input_method: unknown value '$raw' " +
                            "(expected 'full' or 'flypy')",
                    )
                }
                val syllables = node["syllables"]?.sequence?.nodes?.mapNotNull { it.mapping }?.map {
                    Syllable(
                        pinyin = it["pinyin"]?.string
                            ?: throw IllegalArgumentException("t9_disambiguation.syllables: missing 'pinyin'"),
                        t9Code = it["t9_code"]?.string
                            ?: throw IllegalArgumentException("t9_disambiguation.syllables: missing 't9_code'"),
                        flypyCode = it["flypy_code"]?.string ?: "",
                        flypyT9Code = it["flypy_t9_code"]?.string ?: "",
                    )
                } ?: emptyList()
                val flypy = node["flypy_keys"]?.mapping
                return T9Disambiguation(
                    enabled = enabled,
                    inputMethod = inputMethod,
                    keyboard = node["keyboard"]?.string,
                    syllables = syllables,
                    flypyKeys = FlypyKeys(
                        initials = flypy?.get("initials")?.mapping?.pairs
                            ?.mapNotNull { (k, v) -> k.string?.let { it to (v.string ?: "") } }
                            ?.toMap() ?: emptyMap(),
                        finals = flypy?.get("finals")?.mapping?.pairs
                            ?.mapNotNull { (k, v) -> k.string?.let { it to (v.string ?: "") } }
                            ?.toMap() ?: emptyMap(),
                    ),
                )
            }
        }
    }

    companion object {
        /** File suffix for per-schema extension files. */
        const val FILE_SUFFIX = ".extended.yaml"

        /** The custom intent key that marks a schema `tool_bar` as replace. */
        const val TOOL_BAR_REPLACE_KEY = ToolBar.REPLACE_KEY

        /**
         * Parse `<schemaId>.extended.yaml` from [file]. Returns null when the
         * file is absent; throws on malformed content (schema-first: a broken
         * definition must fail loudly, not be silently ignored).
         */
        fun load(file: File): SchemaExtension? {
            if (!file.isFile) return null
            val node = Yaml.Default.parseToYamlNode(file.readText(Charsets.UTF_8)).mapping
                ?: throw IllegalArgumentException("$file is not a YAML mapping")
            return decode(node, file)
        }

        /**
         * Validate an extended file and return a list of problems (empty when
         * valid). Unlike [load] this never throws — it collects all errors,
         * mirroring [com.osfans.trime.data.theme.DefinitionValidator].
         */
        fun validate(yaml: String, source: String = "extended"): List<String> {
            val node =
                try {
                    Yaml.Default.parseToYamlNode(yaml).mapping
                } catch (e: Exception) {
                    return listOf("$source: invalid YAML: ${e.message}")
                } ?: return listOf("$source: extended file must be a YAML mapping")
            val errors = mutableListOf<String>()
            val prefix = "$source:"
            var hasAny = false

            node["schema_id"]?.let { id ->
                hasAny = true
                if (id.string.isNullOrEmpty()) {
                    errors += "$prefix schema_id must be a non-empty string"
                }
            }

            node["tool_bar"]?.let { raw ->
                hasAny = true
                val toolBar = raw.mapping
                if (toolBar == null) {
                    errors += "$prefix tool_bar must be a mapping"
                } else {
                    toolBar[TOOL_BAR_REPLACE_KEY]?.let { replace ->
                        if (replace.boolean == null) {
                            errors += "$prefix tool_bar.$TOOL_BAR_REPLACE_KEY must be a boolean, got '${replace.string}'"
                        }
                    }
                }
            }

            node["t9_disambiguation"]?.let { raw ->
                hasAny = true
                val t9 = raw.mapping
                if (t9 == null) {
                    errors += "$prefix t9_disambiguation must be a mapping"
                } else {
                    t9["enabled"]?.let { enabled ->
                        if (enabled.boolean == null) {
                            errors += "$prefix t9_disambiguation.enabled must be a boolean, got '${enabled.string}'"
                        }
                    }
                    t9["input_method"]?.let { im ->
                        val value = im.string
                        if (value != "full" && value != "flypy") {
                            errors += "$prefix t9_disambiguation.input_method must be 'full' or 'flypy', got '$value'"
                        }
                    }
                }
            }

            if (!hasAny) {
                errors += "$prefix extended file must declare at least one of 'schema_id', 'tool_bar', 't9_disambiguation'"
            }
            return errors
        }

        fun decode(
            node: Node.Mapping,
            source: File? = null,
        ): SchemaExtension {
            val prefix = source?.let { "$it: " } ?: ""
            val schemaId = node["schema_id"]?.string
            val toolBarNode = node["tool_bar"]?.mapping
            val toolBar = ToolBar.decodeExtended(toolBarNode)
            val t9 = T9Disambiguation.decode(node["t9_disambiguation"]?.mapping)
            if (schemaId == null && toolBar == null && t9 == null) {
                throw IllegalArgumentException(
                    "${prefix}extended file must declare at least one of " +
                        "'schema_id', 'tool_bar', 't9_disambiguation'",
                )
            }
            return SchemaExtension(
                schemaId = schemaId,
                toolBar = toolBar,
                toolBarNode = toolBarNode,
                t9Disambiguation = t9,
            )
        }
    }
}
