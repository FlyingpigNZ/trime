// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.util.appContext
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.get
import com.osfans.trime.util.yaml.mapping
import java.io.File

/**
 * Tier-1 app-shipped standard components: preset keys, keyboards, and color
 * schemes. Theme files (tier 2) override/extend these by name at load time.
 */
data class StandardCatalog(
    val presetKeys: Node.Mapping,
    val presetKeyboards: Node.Mapping,
    val presetColorSchemes: Node.Mapping,
) {
    val isEmpty: Boolean
        get() = presetKeys.isEmpty() && presetKeyboards.isEmpty() && presetColorSchemes.isEmpty()

    companion object {
        /**
         * Load the standard catalog from the synced shared data directory,
         * falling back to the packaged assets so the catalog works before the
         * first data sync.
         */
        fun load(sharedDir: File): StandardCatalog? {
            val presetKeys = readSection(sharedDir, "preset_keys", "preset_keys")
            val presetKeyboards = readSection(sharedDir, "keyboards", "preset_keyboards")
            val presetColorSchemes = readSection(sharedDir, "colors", "preset_color_schemes")
            return StandardCatalog(
                presetKeys = presetKeys ?: Node.Mapping(),
                presetKeyboards = presetKeyboards ?: Node.Mapping(),
                presetColorSchemes = presetColorSchemes ?: Node.Mapping(),
            ).takeIf { !it.isEmpty }
        }

        private fun readSection(
            sharedDir: File,
            fileName: String,
            sectionKey: String,
        ): Node.Mapping? {
            val root =
                runCatching {
                    val file = File(sharedDir, "standard/$fileName.yaml")
                    if (file.exists()) {
                        Yaml.parseToYamlNode(file.readText())
                    } else {
                        appContext.assets.open("shared/standard/$fileName.yaml").bufferedReader().use {
                            Yaml.parseToYamlNode(it.readText())
                        }
                    }
                }.getOrNull()
            return root?.get(sectionKey)?.mapping
        }
    }
}
