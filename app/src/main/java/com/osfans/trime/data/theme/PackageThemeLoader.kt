// SPDX-FileCopyrightText: 2015 - 2026 Rime community
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.component.ComponentThemeLoader
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import java.io.File

/**
 * Loads the theme from an IME package workspace.
 *
 * Kept in the theme layer so both [DataManager] and the package manager can
 * check theme usability without forming a package-level dependency cycle.
 */
object PackageThemeLoader {
    /** Whether the workspace contains a theme the app can actually load. */
    fun hasUsableTheme(workspace: File): Boolean = runCatching { load(workspace) }.isSuccess

    fun load(workspace: File): Theme {
        val componentManifest =
            listOf(File(workspace, "component.yaml"), File(workspace, "manifest.yaml"))
                .firstOrNull { it.isFile && ComponentThemeLoader.isComponentManifest(it) }
        if (componentManifest != null) {
            return ComponentThemeLoader.loadTheme(componentManifest)
        }
        val themeFile = File(workspace, "theme.yaml")
        if (themeFile.isFile) {
            val node = Yaml.Default.parseToYamlNode(themeFile.readText(Charsets.UTF_8)).mapping
                ?: throw IllegalArgumentException("theme.yaml is not a mapping")
            val style = node["style"]?.mapping
            val hasColorSchemes =
                node["preset_color_schemes"]?.mapping?.pairs?.isNotEmpty() == true ||
                    node["color_schemes"]?.mapping?.pairs?.isNotEmpty() == true
            if (style == null || style.pairs.isEmpty()) {
                throw IllegalArgumentException("theme.yaml must define a non-empty 'style' section")
            }
            if (!hasColorSchemes) {
                throw IllegalArgumentException("theme.yaml must define at least one color scheme")
            }
            // The runtime decodes preset_keys / preset_keyboards entries as
            // mappings; reject anything else loudly instead of crashing later.
            listOf("preset_keys", "preset_keyboards").forEach { section ->
                node[section]?.mapping?.pairs?.forEach { (key, value) ->
                    if (key.string == null || value.mapping == null) {
                        throw IllegalArgumentException(
                            "theme.yaml $section entries must be mappings, got '${key.string ?: key}'",
                        )
                    }
                }
            }
            val name = node["name"]?.string ?: workspace.name
            return Theme.decode(
                Node.Mapping(
                    LinkedHashMap(node.pairs).apply {
                        put(Node.Scalar("name"), Node.Scalar(name))
                    },
                ),
            )
        }
        throw IllegalArgumentException("IME package has no component manifest or theme.yaml")
    }
}
