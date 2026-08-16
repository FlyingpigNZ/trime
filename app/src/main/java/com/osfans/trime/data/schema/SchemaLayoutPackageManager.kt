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
import com.osfans.trime.util.yaml.string
import java.io.File

/**
 * High-level manager for schema-layout packages.
 *
 * Install/select flow:
 * 1. unpack the zip into a workspace dir under the user data dir and copy theme resources
 * 2. copy Rime files (`rime/...`) from the workspace into the Rime user data dir
 * 3. deploy the auxiliary schemas first, then the main schema (existing JNI)
 * 4. add the schema to `default.custom.yaml` as the first/default input method
 * 5. keep the installed manifest in the registry so layouts can be merged and
 *    `KeyboardSwitcher` can resolve explicit bindings
 */
object SchemaLayoutPackageManager {
    private val installed = mutableMapOf<String, SchemaLayoutManifest>()

    private val mergeableSections =
        setOf("preset_keys", "preset_keyboards", "preset_color_schemes")

    fun install(packageFile: File): SchemaLayoutManifest {
        val manifest = SchemaLayoutPackageInstaller.install(packageFile, installDir())
        copyPackageResources(manifest)
        copyRimeFiles(manifest)
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
        // Rime's user data dir is the canonical location for installed schema
        // sources, so copy the customer schema there before deploying.
        val userSchemaFile = File(DataManager.userDataDir, manifest.schemaFile)
        schemaFile.copyTo(userSchemaFile, overwrite = true)
        // Deploy auxiliary schemas first (e.g. melt_eng, radical_pinyin), then
        // the main schema. Each deployRimeSchemaFile call builds that schema's
        // compiled config and dictionary into the user data build dir.
        val schemaFiles =
            buildList {
                manifest.rimeFiles
                    .map { it.removePrefix("rime/") }
                    .filter { it.endsWith(".schema.yaml") && it != manifest.schemaFile }
                    .forEach { add(it) }
                add(manifest.schemaFile)
            }
        schemaFiles.forEach { name ->
            val file = File(DataManager.userDataDir, name)
            if (!file.isFile) {
                throw IllegalArgumentException("Schema file missing from user data dir: $name")
            }
            if (!Rime.deployRimeSchemaFile(file.absolutePath)) {
                throw IllegalStateException("Rime failed to deploy schema: $name")
            }
        }
        SchemaListUpdater.addSchema(customFile(), schemaId)
        ThemeManager.applySchemaLayout(
            buildLayoutTheme(manifest),
            replaceTheme = manifest.themeFile != null,
        )
    }

    fun registry(): SchemaLayoutRegistry = SchemaLayoutRegistry(installed.toMap())

    fun installedPackage(schemaId: String): SchemaLayoutManifest? = installed[schemaId]

    /**
     * Make package resources (e.g. background images) visible to [ColorManager]
     * by copying them into the user backgrounds directory. Resource paths like
     * `backgrounds/14jian.png` become `backgrounds/14jian.png`.
     */
    private fun copyPackageResources(manifest: SchemaLayoutManifest) {
        val packageDir = File(installDir(), manifest.schemaId)
        val backgroundsDir = File(DataManager.userDataDir, "backgrounds").apply { mkdirs() }
        manifest.resources.forEach { resource ->
            val source = File(packageDir, resource)
            if (!source.isFile) return@forEach
            val relative = resource.removePrefix("backgrounds/").removePrefix("images/")
            val target = File(backgroundsDir, relative)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    /**
     * Make package Rime files visible to the engine by copying them from the
     * package workspace into the Rime user data dir before the workspace
     * deploy runs. Package paths like `rime/default.yaml` become
     * `<user_data_dir>/default.yaml`.
     */
    private fun copyRimeFiles(manifest: SchemaLayoutManifest) {
        val packageDir = File(installDir(), manifest.schemaId)
        val userDataDir = DataManager.userDataDir
        manifest.rimeFiles.forEach { rimeFile ->
            val source = File(packageDir, rimeFile)
            if (!source.isFile) {
                throw IllegalArgumentException("Rime file missing from installed package: $rimeFile")
            }
            val relative = rimeFile.removePrefix("rime/")
            val target = File(userDataDir, relative)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun buildLayoutTheme(manifest: SchemaLayoutManifest): Theme {
        val standard =
            StandardCatalog.load(DataManager.sharedDataDir)
                ?: throw IllegalStateException("Standard catalog unavailable")
        val packageDir = File(installDir(), manifest.schemaId)
        val base =
            if (manifest.themeFile != null) {
                val file = File(packageDir, manifest.themeFile)
                Yaml.parseToYamlNode(file.readText()).mapping
                    ?: throw IllegalArgumentException("Theme is not a mapping: ${manifest.themeFile}")
            } else {
                Node.Mapping(
                    LinkedHashMap<Node, Node>().apply {
                        put(Node.Scalar("name"), Node.Scalar(manifest.name))
                        put(Node.Scalar("style"), Node.Mapping())
                    },
                )
            }
        val combined = LinkedHashMap<Node, Node>()
        for (layoutFile in manifest.layoutFiles) {
            val file = File(packageDir, layoutFile)
            val node =
                Yaml.parseToYamlNode(file.readText()).mapping
                    ?: throw IllegalArgumentException("Layout is not a mapping: $layoutFile")
            // Merge all layout fragments at the node level before resolving, so
            // `__include` references can cross layout files within the package.
            node.pairs.forEach { (key, value) ->
                val keyName = key.string
                if (keyName in mergeableSections) {
                    val existing = combined[key] as? Node.Mapping
                    val incoming = value as? Node.Mapping
                    if (existing != null && incoming != null) {
                        val merged = LinkedHashMap(existing.pairs)
                        incoming.pairs.forEach { (k, v) -> merged[k] = v }
                        combined[key] = Node.Mapping(merged)
                    } else {
                        combined[key] = value
                    }
                } else {
                    combined[key] = value
                }
            }
        }
        return ThemeResolver.mergeSchemaLayout(base, Node.Mapping(combined), standard)
    }

    private fun installDir(): File = File(DataManager.userDataDir, "schema-packages")

    private fun customFile(): File = File(DataManager.userDataDir, "default.custom.yaml")
}
