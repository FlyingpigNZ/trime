// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.data.theme.DefinitionValidator
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeCustomization
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import java.io.File

/**
 * Loads a [Theme] from a self-contained IME package component manifest
 * (`component.yaml` or a `manifest.yaml` with a `components` list).
 *
 * The resolver produces the already-merged definition sections; the resulting
 * [Node.Mapping] is decoded directly by [Theme]. Packages are self-contained,
 * so there is no global standard catalog or external fallback root.
 */
object ComponentThemeLoader {
    /** True when [file] is a component manifest (has a `components` list). */
    fun isComponentManifest(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            val node = Yaml.Default.parseToYamlNode(file.readText(Charsets.UTF_8)).mapping ?: return false
            node["components"]?.sequence != null
        }.getOrDefault(false)
    }

    /** Resolve [manifestFile] into a runtime [Theme]. */
    fun loadTheme(manifestFile: File): Theme {
        val node = Yaml.Default.parseToYamlNode(manifestFile.readText(Charsets.UTF_8)).mapping
            ?: throw IllegalArgumentException("Component manifest is not a mapping: $manifestFile")
        val manifest = ComponentManifest.parse(node)
        val source = ComponentSource.fromDirectory(manifestFile.parentFile ?: File("."))
        val validationErrors = ComponentValidator.validate(manifestFile.readText(Charsets.UTF_8), source)
        if (validationErrors.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid component theme:\n" + validationErrors.joinToString("\n"),
            )
        }
        // App-owned `customization.yaml` is merged after component resolution
        // and validated against the resolved sections before the theme node is
        // built (see ThemeCustomization for the merge semantics).
        val resolved = ComponentResolver(source).resolve(manifest)
        val sections =
            ThemeCustomization.load(manifestFile.parentFile ?: File("."))?.let { customization ->
                val errors = DefinitionValidator.validateCustomization(resolved, customization)
                if (errors.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "Invalid ${ThemeCustomization.FILE_NAME}:\n" + errors.joinToString("\n"),
                    )
                }
                ThemeCustomization.applyColorSchemeOverrides(resolved, customization)
            } ?: resolved
        // Per-schema `<schemaId>.extended.yaml` files (app-owned directives).
        val extendedErrors =
            DefinitionValidator.validateExtendedFiles(
                manifestFile.parentFile ?: File("."),
                sections,
            )
        if (extendedErrors.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid extended files:\n" + extendedErrors.joinToString("\n"),
            )
        }
        return Theme.decode(buildThemeNode(manifest, sections))
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
