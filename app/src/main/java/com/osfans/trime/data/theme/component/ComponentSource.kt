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
        const val MISSING_FILE_ERROR_PREFIX = "Component file not found: "

        /** Filesystem-backed source rooted at [root]. */
        fun fromDirectory(root: File): ComponentSource = ComponentSource { path ->
            val file = File(root, path)
            if (!file.isFile) {
                throw IllegalArgumentException(MISSING_FILE_ERROR_PREFIX + path)
            }
            val node = Yaml.Default.parseToYamlNode(file.readText(Charsets.UTF_8))
            node.mapping
                ?: throw IllegalArgumentException("Component file is not a mapping: $path")
        }

        /** In-memory source for tests. */
        fun fromMap(files: Map<String, Node.Mapping>): ComponentSource = ComponentSource { path ->
            files[path]
                ?: throw IllegalArgumentException(MISSING_FILE_ERROR_PREFIX + path)
        }
    }
}
