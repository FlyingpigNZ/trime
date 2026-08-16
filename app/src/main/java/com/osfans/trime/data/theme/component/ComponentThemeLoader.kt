// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.data.theme.StandardCatalog
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeResolver
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import java.io.File

/**
 * Loads a [Theme] from a component manifest (`component.yaml` or a
 * `manifest.yaml` with a `components` list).
 *
 * The resolver produces the already-merged definition sections (standard +
 * shared-aux + theme deltas). The resulting [Node.Mapping] is then decoded by
 * the same `ThemeResolver` / `Theme` path used by monolithic themes.
 */
object ComponentThemeLoader {
    /** True when [file] is a component manifest (has a `components` list). */
    fun isComponentManifest(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            val node = Yaml.Default.parseToYamlNode(file.readText()).mapping ?: return false
            node["components"]?.sequence != null
        }.getOrDefault(false)
    }

    /**
     * Resolve [manifestFile] into a runtime [Theme].
     *
     * [fallbackRoot] is the shared data root used to resolve `standard/...`
     * files when they are not relative to the manifest's own directory.
     */
    fun loadTheme(
        manifestFile: File,
        standard: StandardCatalog?,
        fallbackRoot: File? = null,
    ): Theme {
        val node = Yaml.Default.parseToYamlNode(manifestFile.readText()).mapping
            ?: throw IllegalArgumentException("Component manifest is not a mapping: $manifestFile")
        val manifest = ComponentManifest.parse(node)
        val primary = ComponentSource.fromDirectory(manifestFile.parentFile ?: File("."))
        val source =
            if (fallbackRoot != null) {
                ComponentSource.fallback(primary, ComponentSource.fromDirectory(fallbackRoot))
            } else {
                primary
            }
        val validationErrors = ComponentValidator.validate(manifestFile.readText(), source)
        if (validationErrors.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid component theme:\n" + validationErrors.joinToString("\n"),
            )
        }
        val sections = ComponentResolver(source).resolve(manifest)
        val themeNode = buildThemeNode(manifest, sections)
        return if (standard != null) {
            ThemeResolver.resolve(themeNode, standard)
        } else {
            Theme.decode(themeNode)
        }
    }

    private fun buildThemeNode(
        manifest: ComponentManifest,
        sections: Map<String, Node.Mapping>,
    ): Node.Mapping {
        val pairs = LinkedHashMap<Node, Node>()
        pairs[Node.Scalar("name")] = Node.Scalar(manifest.name)
        manifest.author?.let { pairs[Node.Scalar("author")] = Node.Scalar(it) }
        THEME_SECTIONS.forEach { section ->
            sections[section]?.takeIf { it.pairs.isNotEmpty() }?.let {
                pairs[Node.Scalar(section)] = it
            }
        }
        return Node.Mapping(pairs)
    }

    private val THEME_SECTIONS =
        listOf(
            "preset_keys",
            "preset_keyboards",
            "preset_color_schemes",
            "style",
            "preedit",
            "window",
            "tool_bar",
            "liquid_keyboard",
            "fallback_colors",
        )
}
