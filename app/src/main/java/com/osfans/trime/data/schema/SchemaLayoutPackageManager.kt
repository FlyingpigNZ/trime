// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import com.osfans.trime.core.Rime
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.StandardCatalog
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeResolver
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import java.io.File

/**
 * High-level manager for schema-layout packages.
 *
 * Install/select flow:
 * 1. unpack the zip into the user data dir
 * 2. compile the schema through Rime (JNI)
 * 3. add the schema to `default.custom.yaml` as the first/default input method
 * 4. keep the installed manifest in the registry so layouts can be merged and
 *    `KeyboardSwitcher` can resolve explicit bindings
 */
object SchemaLayoutPackageManager {
    private val installed = mutableMapOf<String, SchemaLayoutManifest>()

    fun install(packageFile: File): SchemaLayoutManifest {
        val manifest = SchemaLayoutPackageInstaller.install(packageFile, installDir())
        installed[manifest.schemaId] = manifest
        return manifest
    }

    /** Install a new package and immediately select it as the default schema. */
    fun selectPackage(packageFile: File) {
        val manifest = install(packageFile)
        select(manifest.schemaId)
    }

    /** Select an already-installed schema as the default input method. */
    fun select(schemaId: String) {
        val manifest = installed[schemaId]
            ?: throw IllegalArgumentException("Schema layout package not installed: $schemaId")
        val schemaFile = File(installDir(), schemaId).resolve(manifest.schemaFile)
        if (!schemaFile.isFile) {
            throw IllegalArgumentException("Schema file missing for package: $schemaId")
        }
        if (!Rime.deployRimeSchemaFile(schemaFile.absolutePath)) {
            throw IllegalStateException("Rime failed to deploy schema: $schemaId")
        }
        SchemaListUpdater.addSchema(customFile(), schemaId)
        ThemeManager.applySchemaLayout(buildLayoutTheme(manifest))
    }

    fun registry(): SchemaLayoutRegistry = SchemaLayoutRegistry(installed.toMap())

    fun installedPackage(schemaId: String): SchemaLayoutManifest? = installed[schemaId]

    private fun buildLayoutTheme(manifest: SchemaLayoutManifest): Theme {
        val standard =
            StandardCatalog.load(DataManager.sharedDataDir)
                ?: throw IllegalStateException("Standard catalog unavailable")
        var merged: Theme? = null
        for (layoutFile in manifest.layoutFiles) {
            val file = File(File(installDir(), manifest.schemaId), layoutFile)
            val node =
                Yaml.parseToYamlNode(file.readText()).mapping
                    ?: throw IllegalArgumentException("Layout is not a mapping: $layoutFile")
            val complete = Node.Mapping(LinkedHashMap(node.pairs).apply {
                putIfAbsent(Node.Scalar("name"), Node.Scalar(manifest.name))
                putIfAbsent(Node.Scalar("style"), Node.Mapping())
            })
            val resolved = ThemeResolver.resolve(complete, standard)
            merged = if (merged == null) resolved else merged.mergeSchemaLayout(resolved)
        }
        return merged ?: throw IllegalArgumentException("Package has no layout files")
    }

    private fun installDir(): File = File(DataManager.userDataDir, "schema-packages")

    private fun customFile(): File = File(DataManager.userDataDir, "default.custom.yaml")
}
