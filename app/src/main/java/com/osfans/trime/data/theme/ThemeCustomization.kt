// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import java.io.File
import org.yaml.snakeyaml.Yaml as SnakeYaml

/**
 * App-owned per-workspace customization overlay (`customization.yaml`).
 *
 * The file lives at the workspace root next to the component manifest. It is
 * written by the app and never shipped inside a package zip, so package
 * re-import/update (overlay extraction) leaves it untouched while the base
 * `color.yaml` stays pristine.
 *
 * Schema (first version — color overrides only):
 *
 * ```
 * color_schemes:
 *   <schemeId>:                 # must exist in the resolved theme; may contain '/'
 *     light:                    # partial palette override for the light mode
 *       <colorKey>: <value>
 *     dark:                     # partial palette override for the dark mode
 *       <colorKey>: <value>
 * ```
 *
 * Overrides are applied after component resolution and before `Theme.decode`.
 * Scheme entries without explicit `light`/`dark` sub-mappings (legacy flat
 * `preset_color_schemes`) are first materialized into `light`/`dark` palette
 * copies so a per-mode override can never leak into the shared flat map; a
 * scheme whose dark palette is missing gets a dark copy of its light palette
 * (mirroring runtime fallback semantics). Edits are therefore scoped to one
 * scheme × mode, and base palettes shared by several schemes are never
 * mutated.
 */
object ThemeCustomization {
    const val FILE_NAME = "customization.yaml"
    const val COLOR_SCHEMES_KEY = "color_schemes"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val BACKGROUND_DIR_NAME = "backgrounds"

    private const val NAME = "name"
    private const val PRESET_COLOR_SCHEMES = "preset_color_schemes"
    private const val KEYBOARD_BACKGROUND_KEY = "keyboard_background"
    private val SCHEME_KEYS_TO_STRIP = setOf(MODE_LIGHT, MODE_DARK, NAME, AUTHOR)
    private const val AUTHOR = "author"

    /** Returns the parsed overlay, or null when the workspace has no file. */
    fun load(workspace: File): Node.Mapping? {
        val file = File(workspace, FILE_NAME)
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return Node.Mapping()
        val node =
            try {
                Yaml.Default.parseToYamlNode(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("$FILE_NAME: ${e.message}", e)
            }
        return node.mapping
            ?: throw IllegalArgumentException("$FILE_NAME must be a YAML mapping")
    }

    /**
     * Atomically persist [customization] into the workspace. The app-owned
     * file is machine-written, so a structured SnakeYAML dump (which drops
     * comments) is acceptable; the write goes through a temp file + rename so
     * a crash cannot leave a truncated overlay.
     */
    fun write(
        workspace: File,
        customization: Node.Mapping,
    ) {
        val dir = workspace.apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        val text =
            if (customization.pairs.isEmpty()) {
                ""
            } else {
                SnakeYaml().dump(toPlainMapping(customization))
            }
        val tmp = File(dir, "$FILE_NAME.${System.nanoTime()}.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeText(text, Charsets.UTF_8)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Deep-merge [customization]'s `color_schemes` overrides onto the resolved
     * component [sections]. Returns a new sections map when anything changed;
     * the input sections and their base palettes are never mutated.
     */
    fun applyColorSchemeOverrides(
        sections: Map<String, Node.Mapping>,
        customization: Node.Mapping,
    ): Map<String, Node.Mapping> {
        val overrides = customization[Node.Scalar(COLOR_SCHEMES_KEY)]?.mapping ?: return sections
        if (overrides.pairs.isEmpty()) return sections
        val preset = sections[PRESET_COLOR_SCHEMES]?.mapping ?: return sections
        val newPresetPairs = LinkedHashMap<Node, Node>(preset.pairs)
        var changed = false
        overrides.pairs.forEach { (idNode, overrideNode) ->
            val id = idNode.string ?: return@forEach
            val override = overrideNode.mapping ?: return@forEach
            val schemeKey = Node.Scalar(id)
            val base = newPresetPairs[schemeKey]?.mapping ?: return@forEach
            newPresetPairs[schemeKey] = mergeScheme(base, override)
            changed = true
        }
        if (!changed) return sections
        val newSections = LinkedHashMap(sections)
        newSections[PRESET_COLOR_SCHEMES] = Node.Mapping(newPresetPairs)
        return newSections
    }

    /**
     * Returns a new overlay mapping with `keyboard_background` set to
     * [fileName] for (`schemeId`, [mode]), or removed when [fileName] is null.
     * Empty mode/scheme branches are pruned, so a fully reverted overlay
     * serializes to an empty file.
     */
    fun updatedColorSchemeBackground(
        customization: Node.Mapping?,
        schemeId: String,
        mode: String,
        fileName: String?,
    ): Node.Mapping {
        require(mode == MODE_LIGHT || mode == MODE_DARK) { "Unsupported mode: $mode" }
        val backgroundKey = Node.Scalar(KEYBOARD_BACKGROUND_KEY)
        val schemeKey = Node.Scalar(schemeId)
        val modeKey = Node.Scalar(mode)
        val schemesPairs =
            LinkedHashMap<Node, Node>(
                customization?.get(Node.Scalar(COLOR_SCHEMES_KEY))?.mapping?.pairs ?: emptyMap(),
            )
        val schemePairs = LinkedHashMap<Node, Node>(schemesPairs[schemeKey]?.mapping?.pairs ?: emptyMap())
        val palettePairs = LinkedHashMap<Node, Node>(schemePairs[modeKey]?.mapping?.pairs ?: emptyMap())
        if (fileName == null) {
            palettePairs.remove(backgroundKey)
        } else {
            palettePairs[backgroundKey] = Node.Scalar(fileName)
        }
        if (palettePairs.isEmpty()) {
            schemePairs.remove(modeKey)
        } else {
            schemePairs[modeKey] = Node.Mapping(palettePairs)
        }
        if (schemePairs.isEmpty()) {
            schemesPairs.remove(schemeKey)
        } else {
            schemesPairs[schemeKey] = Node.Mapping(schemePairs)
        }
        val rootPairs = LinkedHashMap<Node, Node>(customization?.pairs ?: emptyMap())
        if (schemesPairs.isEmpty()) {
            rootPairs.remove(Node.Scalar(COLOR_SCHEMES_KEY))
        } else {
            rootPairs[Node.Scalar(COLOR_SCHEMES_KEY)] = Node.Mapping(schemesPairs)
        }
        return Node.Mapping(rootPairs)
    }

    /**
     * Deterministic slot file name for one scheme × mode, e.g.
     * `custom_default_dark_temple_night.png`. See [nextSlotFileName] for
     * why a second generation exists. The `custom_` prefix keeps the slot
     * clear of package-shipped images.
     */
    fun slotFileName(
        schemeId: String,
        mode: String,
        extension: String = "png",
    ): String {
        val tag = when (mode) {
            MODE_LIGHT -> "day"
            MODE_DARK -> "night"
            else -> throw IllegalArgumentException("Unsupported mode: $mode")
        }
        return "custom_${sanitizeSchemeId(schemeId)}_$tag.$extension"
    }

    /**
     * Second slot generation, e.g. `custom_default_dark_temple_night_2.png`.
     *
     * Re-applying a background overwrites the same path, so the Theme data
     * (the stored file *name*) stays equal and the running IME would not
     * rebuild its views on its own. Applying therefore alternates between two
     * slot files: every apply changes the stored name, which is a real data
     * change, so the normal theme-change path refreshes the open keyboard —
     * no forced redraw and no stale decode of an overwritten path.
     */
    fun secondSlotFileName(
        schemeId: String,
        mode: String,
        extension: String = "png",
    ): String {
        val base = slotFileName(schemeId, mode, extension).substringBeforeLast('.')
        return "${base}_2.$extension"
    }

    /** The file to write next, alternating away from [currentFileName]. */
    fun nextSlotFileName(
        schemeId: String,
        mode: String,
        currentFileName: String?,
    ): String = if (currentFileName == slotFileName(schemeId, mode)) {
        secondSlotFileName(schemeId, mode)
    } else {
        slotFileName(schemeId, mode)
    }

    /** Both slot generations of one scheme × mode (for cleanup). */
    fun slotFileNameVariants(
        schemeId: String,
        mode: String,
        extension: String = "png",
    ): List<String> = listOf(
        slotFileName(schemeId, mode, extension),
        secondSlotFileName(schemeId, mode, extension),
    )

    /** Characters not valid in a file name are folded into underscores. */
    fun sanitizeSchemeId(schemeId: String): String = schemeId.map { c ->
        if (c.isLetterOrDigit() || c == '_' || c == '.' || c == '-') c else '_'
    }.joinToString("")

    private fun mergeScheme(
        base: Node.Mapping,
        override: Node.Mapping,
    ): Node.Mapping {
        val result = LinkedHashMap<Node, Node>(base.pairs)
        val hasModeSubMaps = result.containsKey(Node.Scalar(MODE_LIGHT)) || result.containsKey(Node.Scalar(MODE_DARK))
        if (!hasModeSubMaps) {
            // Legacy flat scheme entry: materialize into light/dark palette
            // copies so a per-mode override is isolated from the flat map.
            val content = flatPaletteContent(result)
            result[Node.Scalar(MODE_LIGHT)] = content
            result[Node.Scalar(MODE_DARK)] = content
        }
        if (result[Node.Scalar(MODE_DARK)] == null) {
            // Missing dark palette falls back to a copy of the light palette
            // (runtime parity), which also lets users customize the dark mode
            // of schemes whose color.yaml only declares `light`.
            result[Node.Scalar(MODE_DARK)] = result[Node.Scalar(MODE_LIGHT)] ?: Node.Mapping()
        }
        listOf(MODE_LIGHT, MODE_DARK).forEach { mode ->
            val partial = override[Node.Scalar(mode)]?.mapping ?: return@forEach
            val current = result[Node.Scalar(mode)]?.mapping ?: Node.Mapping()
            result[Node.Scalar(mode)] = mergeMapping(current, partial)
        }
        return Node.Mapping(result)
    }

    /** Mapping-recursive merge; scalars and sequences in [override] win. */
    private fun mergeMapping(
        base: Node.Mapping,
        override: Node.Mapping,
    ): Node.Mapping {
        val result = LinkedHashMap<Node, Node>(base.pairs)
        override.pairs.forEach { (key, value) ->
            val existing = result[key]
            result[key] = if (existing is Node.Mapping && value is Node.Mapping) {
                mergeMapping(existing, value)
            } else {
                value
            }
        }
        return Node.Mapping(result)
    }

    /**
     * Palette content of a legacy flat scheme entry: everything except the
     * `light`/`dark` mode keys and the top-level `name` metadata key.
     */
    private fun flatPaletteContent(pairs: Map<Node, Node>): Node.Mapping = Node.Mapping(pairs.filterKeys { it.string !in SCHEME_KEYS_TO_STRIP })

    private fun toPlainMapping(node: Node): Any = when (node) {
        is Node.Mapping -> {
            val map = LinkedHashMap<String, Any>()
            node.pairs.forEach { (key, value) ->
                val keyString = (key as? Node.Scalar)?.string ?: key.toString()
                map[keyString] = toPlainMapping(value)
            }
            map
        }
        is Node.Sequence -> node.nodes.map { toPlainMapping(it) }
        is Node.Scalar -> node.string
        is Node.Alias -> node.anchor
    }
}
