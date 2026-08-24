// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import com.osfans.trime.data.theme.PackageThemeLoader
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import java.io.File

/**
 * Facade over the IME package model. Package operations live in focused
 * collaborators:
 * - [PackageMetadata] — zip/manifest parsing and file utilities;
 * - [PackageArchive] — import/export/overlay extraction;
 * - [PackageCompiler] — `:compile` process coordination, marker bookkeeping,
 *   theme reload;
 * - [PackageActivator] — activation and Default-package startup readiness.
 *
 * This object keeps the public API (and the small query surface) stable.
 */
object ImePackageManager {
    const val DEFAULT_PACKAGE_FILE_NAME = PackageStore.DEFAULT_PACKAGE_FILE_NAME

    data class ImePackage(
        val fileName: String,
        val name: String,
        val version: String?,
        val error: String? = null,
        /** Whether the workspace carries a valid compiled.marker. */
        val compiled: Boolean = false,
    )

    fun listPackages(): List<ImePackage> = PackageStore.rootDir
        .listFiles { file -> file.isDirectory }
        ?.mapNotNull { dir ->
            val id = dir.name
            if (!PackageStore.isSafePackageId(id)) return@mapNotNull null
            if (id == PackageStore.MIGRATED_PACKAGE_ID) return@mapNotNull null
            val zip = File(dir, "package.zip")
            val meta =
                if (zip.isFile) {
                    runCatching { PackageMetadata.readPackageMeta(zip) }.getOrNull()
                } else {
                    runCatching { PackageMetadata.readWorkspaceMeta(PackageStore.workspaceDir(id)) }.getOrNull()
                }
            ImePackage(
                fileName = "$id.zip",
                name = meta?.name ?: id,
                version = meta?.version,
                error = if (meta == null) "Invalid or missing package.zip" else null,
                // Precomputed here (caller runs this on IO) so the list
                // adapter never stats the workspace from the main thread.
                compiled = PackageStore.isCompiled(id),
            )
        }
        ?.sortedBy { it.fileName.lowercase() }
        ?: emptyList()

    fun activePackageFileName(): String? = PackageStore.activePackageId()?.let { "$it.zip" }

    fun packageFile(fileName: String): File {
        val id = fileName.removeSuffix(".zip")
        return File(PackageStore.packageDir(id), "package.zip")
    }

    /** Resolve the schema/package id from an imported package zip. */
    fun packageIdOf(packageFile: File): String = PackageMetadata.readPackageMeta(packageFile).schemaId

    fun isDefaultPackage(fileName: String): Boolean = fileName == DEFAULT_PACKAGE_FILE_NAME

    /** Whether the workspace contains a theme the app can actually load. */
    fun hasUsableTheme(workspace: File): Boolean = PackageThemeLoader.hasUsableTheme(workspace)

    fun isActivePackage(packageFile: File): Boolean = (packageFile.parentFile?.name ?: packageFile.nameWithoutExtension) == PackageStore.activePackageId()

    fun deletePackage(fileName: String): Boolean {
        if (isDefaultPackage(fileName)) return false
        val id = fileName.removeSuffix(".zip")
        if (!PackageStore.isSafePackageId(id)) return false
        val dir = PackageStore.packageDir(id)
        if (!dir.isDirectory) return false
        if (id == PackageStore.activePackageId()) return false
        dir.deleteRecursively()
        return true
    }

    fun exportPackage(fileName: String): File = PackageArchive.exportPackage(fileName)

    fun importPackage(source: File): File = PackageArchive.importPackage(source)

    fun installBundledDefaultPackage() = PackageActivator.installBundledDefaultPackage()

    suspend fun ensureDefaultPackageReady() = PackageActivator.ensureDefaultPackageReady()

    fun isCompiled(fileName: String): Boolean = PackageCompiler.isCompiled(fileName)

    fun markCurrentWorkspaceCompiled() = PackageCompiler.markCurrentWorkspaceCompiled()

    suspend fun compilePackageFile(fileName: String) = PackageCompiler.compilePackageFile(fileName)

    suspend fun activate(
        packageFile: File,
        applyThemeAfter: Boolean = true,
    ) = PackageActivator.activate(packageFile, applyThemeAfter)

    /** True while a package is being compiled/activated; input should be blocked. */
    fun isActivating(): Boolean = PackageActivator.isActivating()

    /** True while a compile/activation is running; UI should block new imports. */
    fun isBusy(): Boolean = PackageActivator.isActivating() || PackageCompiler.isCompiling()

    /** Registry view of the active package's schema → keyboard binding. */
    fun registry(): DefaultKeyboardRegistry {
        val workspace = PackageStore.activeWorkspaceDir() ?: return DefaultKeyboardRegistry.Empty
        val manifest = listOf(File(workspace, "manifest.yaml"), File(workspace, "component.yaml"))
            .firstOrNull { it.isFile }
            ?: return DefaultKeyboardRegistry.Empty
        val node = runCatching {
            Yaml.Default.parseToYamlNode(manifest.readText(Charsets.UTF_8)).mapping
        }.getOrNull() ?: return DefaultKeyboardRegistry.Empty
        val schemaId = node["schema_id"]?.string ?: return DefaultKeyboardRegistry.Empty
        val defaultKeyboard = node["default_keyboard"]?.string ?: return DefaultKeyboardRegistry.Empty
        return DefaultKeyboardRegistry.fromDefaultKeyboards(mapOf(schemaId to defaultKeyboard))
    }

    fun defaultKeyboardFor(schemaId: String): String? = registry().defaultKeyboardFor(schemaId)
}
