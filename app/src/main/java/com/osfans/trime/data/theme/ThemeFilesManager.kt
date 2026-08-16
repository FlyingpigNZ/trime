/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.component.ComponentThemeLoader
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import timber.log.Timber
import java.io.File

object ThemeFilesManager {
    fun listThemes(dir: File): MutableList<ThemeItem> {
        val result = mutableListOf<ThemeItem>()
        val deployedMap = hashMapOf<String, String>()
        DataManager.stagingDir.list()?.forEach {
            deployedMap[it] = it
        }
        DataManager.prebuiltDataDir.list()?.forEach {
            deployedMap[it] = it
        }

        // Legacy monolithic themes: *.trime.yaml
        val files = dir.listFiles { _, name -> name.endsWith("trime.yaml") } ?: emptyArray()
        files
            .sortedByDescending { it.lastModified() }
            .mapNotNull decode@{
                val item =
                    runCatching {
                        val configId = it.nameWithoutExtension
                        val name =
                            if (deployedMap[it.name] != null) {
                                val file = File(DataManager.resolveDeployedResourcePath(configId))
                                val node = Yaml.parseToYamlNode(file.readText()).mapping
                                node?.get("name")?.string ?: return@decode null
                            } else {
                                configId.removeSuffix(".trime")
                            }
                        ThemeItem(configId, name)
                    }.getOrElse { e ->
                        Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                        return@decode null
                    }
                return@decode item
            }
            .forEach(result::add)

        // Component themes: <id>.component.yaml
        dir.listFiles { f -> f.isFile && f.name.endsWith(".component.yaml") }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                val configId = file.name.removeSuffix(".component.yaml")
                val name = readComponentName(file) ?: configId
                result += ThemeItem(configId, name)
            }

        // Component themes: <id>/component.yaml or <id>/manifest.yaml with components
        dir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { subdir ->
                val manifest =
                    listOf(File(subdir, "component.yaml"), File(subdir, "manifest.yaml"))
                        .firstOrNull { it.isFile && ComponentThemeLoader.isComponentManifest(it) }
                        ?: return@forEach
                val name = readComponentName(manifest) ?: subdir.name
                result += ThemeItem(subdir.name, name)
            }

        return result
    }

    /** Find a component manifest for a theme id in shared or user data. */
    fun findComponentManifest(id: String): File? {
        listOf(DataManager.sharedDataDir, DataManager.userDataDir).forEach { dir ->
            val direct = File(dir, "$id.component.yaml")
            if (direct.isFile && ComponentThemeLoader.isComponentManifest(direct)) {
                return direct
            }
            val subdir = File(dir, id)
            val component = File(subdir, "component.yaml")
            if (component.isFile && ComponentThemeLoader.isComponentManifest(component)) {
                return component
            }
            val manifest = File(subdir, "manifest.yaml")
            if (manifest.isFile && ComponentThemeLoader.isComponentManifest(manifest)) {
                return manifest
            }
        }
        return null
    }

    private fun readComponentName(file: File): String? =
        runCatching {
            Yaml.parseToYamlNode(file.readText()).mapping?.get("name")?.string
        }.getOrNull()
}
