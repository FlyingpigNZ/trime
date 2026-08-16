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
 * 1. unpack the zip into the user data dir
 * 2. compile the schema through Rime (JNI)
 * 3. add the schema to `default.custom.yaml` as the first/default input method
 * 4. keep the installed manifest in the registry so layouts can be merged and
 *    `KeyboardSwitcher` can resolve explicit bindings
 */
object SchemaLayoutPackageManager {
    private val installed = mutableMapOf<String, SchemaLayoutManifest>()

    private val mergeableSections =
        setOf("preset_keys", "preset_keyboards", "preset_color_schemes")

    fun install(packageFile: File): SchemaLayoutManifest {
        val manifest = SchemaLayoutPackageInstaller.install(packageFile, installDir())
        copyPackageResources(manifest)
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

    private fun buildLayoutTheme(manifest: SchemaLayoutManifest): Theme {
        val standard =
            StandardCatalog.load(DataManager.sharedDataDir)
                ?: throw IllegalStateException("Standard catalog unavailable")
        val combined = LinkedHashMap<Node, Node>()
        for (layoutFile in manifest.layoutFiles) {
            val file = File(File(installDir(), manifest.schemaId), layoutFile)
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
        combined.putIfAbsent(Node.Scalar("name"), Node.Scalar(manifest.name))
        combined.putIfAbsent(Node.Scalar("style"), Node.Mapping())
        return ThemeResolver.resolve(Node.Mapping(combined), standard)
    }

    private fun installDir(): File = File(DataManager.userDataDir, "schema-packages")

    private fun customFile(): File = File(DataManager.userDataDir, "default.custom.yaml")
}
