// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import java.io.File

/**
 * Loads component files by relative path.
 *
 * The resolver is pure except for this source abstraction, which keeps unit
 * tests independent from the real filesystem.
 */
fun interface ComponentSource {
    /** Parse a YAML file into a mapping. */
    fun load(path: String): Node.Mapping

    companion object {
        /** Filesystem-backed source rooted at [root]. */
        fun fromDirectory(root: File): ComponentSource = ComponentSource { path ->
            val file = File(root, path)
            if (!file.isFile) {
                throw IllegalArgumentException("Component file not found: $path")
            }
            val node = Yaml.Default.parseToYamlNode(file.readText())
            node.mapping
                ?: throw IllegalArgumentException("Component file is not a mapping: $path")
        }

        /** In-memory source for tests. */
        fun fromMap(files: Map<String, Node.Mapping>): ComponentSource = ComponentSource { path ->
            files[path]
                ?: throw IllegalArgumentException("Component file not found: $path")
        }

        /**
         * Try [primary] first; if it cannot find the file, try [fallback].
         *
         * This lets a theme-relative source resolve `../shared-aux/...` while
         * still resolving `standard/...` from the shared data root.
         */
        fun fallback(
            primary: ComponentSource,
            fallback: ComponentSource,
        ): ComponentSource = ComponentSource { path ->
            try {
                primary.load(path)
            } catch (_: IllegalArgumentException) {
                fallback.load(path)
            }
        }
    }
}
