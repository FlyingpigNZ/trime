// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Import/export of self-contained IME package zips against the workspace
 * package library. Importing overlays the zip onto the package's workspace
 * without deleting it (user data is preserved); exporting snapshots the
 * workspace back into a zip, excluding compile artifacts.
 *
 * Split out of [ImePackageManager].
 */
internal object PackageArchive {
    /**
     * Import a package zip into the package library and extract it into its
     * own workspace. This does not compile or activate.
     */
    fun importPackage(source: File): File {
        val meta = PackageMetadata.readPackageMeta(source)
        PackageMetadata.requireSafePackageId(meta.schemaId)
        // Validate the package contents before touching the library/workspace.
        // This must happen before any write so a bad package cannot leave a
        // half-imported directory behind.
        val schemaIds = PackageMetadata.schemaIdsFromZip(source, meta.schemaId)
        // Entry-path validation must also happen before the first write:
        // otherwise a zip with a valid manifest but a malicious entry would
        // already have overwritten the existing package.zip by the time the
        // overlay extraction throws.
        validateZipEntryPaths(source)

        val packageDir = PackageStore.packageDir(meta.schemaId)
        val workspace = PackageStore.workspaceDir(meta.schemaId)
        val isNewPackage = !workspace.isDirectory
        try {
            packageDir.mkdirs()
            val zip = File(packageDir, "package.zip")
            source.copyTo(zip, overwrite = true)

            // Overlay-extract into the existing workspace: files owned by the
            // new package overwrite old versions, while files not in the
            // package (user data, user dictionaries, sync, custom resources,
            // etc.) are preserved automatically because we never delete the
            // workspace.
            workspace.mkdirs()
            // Invalidate compile state before overlay extraction: if extraction
            // fails, the workspace must not look compiled.
            File(workspace, "compiled.marker").delete()
            File(workspace, "compiled.error").delete()
            extractZipOverlay(zip, workspace)
            PackageMetadata.writeWorkspaceSchemaList(workspace, schemaIds)
            return zip
        } catch (t: Throwable) {
            if (isNewPackage) {
                runCatching { packageDir.deleteRecursively() }
            } else {
                // Keep the existing workspace visible, but never let a failed
                // overlay import look like a healthy compiled package.
                runCatching {
                    File(workspace, "compiled.marker").delete()
                    File(workspace, "compiled.error").writeText("import failed\n")
                }
            }
            throw t
        }
    }

    /**
     * Export a workspace as a complete zip snapshot (including user data).
     *
     * The exported zip can be fed back into [importPackage]. `build/` and
     * compile markers are excluded; if the workspace has no manifest, a minimal
     * manifest is generated so the export remains importable.
     *
     * Note: re-importing an exported workspace creates a package whose id comes
     * from its manifest `schema_id`. In particular, exporting `Default` and
     * re-importing it produces a package named e.g. `luna_pinyin`, not
     * `Default`; this is the normal "package id = schema_id" import semantics.
     */
    fun exportPackage(fileName: String): File {
        val id = fileName.removeSuffix(".zip")
        PackageMetadata.requireSafePackageId(id)
        val workspace = PackageStore.workspaceDir(id)
        if (!workspace.isDirectory) {
            throw IllegalArgumentException("Package workspace missing: $workspace")
        }
        val out = File(workspace.parentFile, "$id-export-${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zipOut ->
                val hasManifest =
                    File(workspace, "manifest.yaml").isFile ||
                        File(workspace, "component.yaml").isFile
                if (!hasManifest) {
                    val schemaId = PackageMetadata.resolveMinimalManifestSchemaId(workspace, id)
                    zipOut.putNextEntry(ZipEntry("manifest.yaml"))
                    zipOut.write(PackageMetadata.minimalManifest(schemaId).toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                }
                workspace.walkTopDown().forEach { file ->
                    if (!file.isFile) return@forEach
                    val relative = file.relativeTo(workspace).path
                    if (PackageMetadata.isCompileArtifact(relative)) {
                        return@forEach
                    }
                    zipOut.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        return out
    }

    /**
     * Reject absolute paths and parent traversal in every zip entry before
     * any write to the package library/workspace.
     */
    private fun validateZipEntryPaths(zip: File) {
        ZipFile(zip).use { z ->
            z.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                    throw IllegalArgumentException("Unsafe path in IME package: $name")
                }
            }
        }
    }

    /**
     * Overlay-extract [zip] into [workspace] without deleting existing files.
     * Package files overwrite old versions; files not present in the zip are
     * left untouched, which preserves user data across re-import/update.
     */
    internal fun extractZipOverlay(
        zip: File,
        workspace: File,
    ) {
        ZipFile(zip).use { z ->
            val workspaceCanonical = workspace.canonicalPath
            z.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                // Reject absolute paths and parent traversal before touching
                // the disk (java.nio.file.Path is API 26+; minSdk is 21).
                if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                    throw IllegalArgumentException("Unsafe path in IME package: $name")
                }
                val relative =
                    if (name.startsWith("rime/")) name.removePrefix("rime/") else name
                // Never let a package inject compile state: markers and deploy
                // output are owned by the compile flow, not package data.
                if (PackageMetadata.isCompileArtifact(relative)) {
                    return@forEach
                }
                val target = File(workspace, relative)
                if (!target.canonicalPath.startsWith(workspaceCanonical + File.separator)) {
                    throw IllegalArgumentException("Unsafe path in IME package: $name")
                }
                target.parentFile?.mkdirs()
                z.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
