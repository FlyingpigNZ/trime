// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import com.osfans.trime.util.DiagnosticLog
import java.io.File
import java.io.OutputStream
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
            DiagnosticLog.i(
                "pkg",
                "import id=${meta.schemaId} invalidate-marker ws=${workspace.absolutePath} isNew=$isNewPackage",
            )
            File(workspace, "compiled.marker").delete()
            File(workspace, "compiled.error").delete()
            extractZipOverlay(zip, workspace)
            PackageMetadata.writeWorkspaceSchemaList(workspace, schemaIds)
            return zip
        } catch (t: Throwable) {
            DiagnosticLog.e("pkg", "import failed id=${meta.schemaId} isNew=$isNewPackage", t)
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
     * Export a workspace as a complete zip snapshot (including user data),
     * streaming it into [out].
     *
     * This is the only export writer on purpose. Callers write straight into
     * the destination they already have — the SAF document the user picked
     * (manual export) or the backup document in the chosen folder (scheduled
     * backup) — so a large workspace is never zipped into a temporary file
     * first: that used to double the disk usage, could leave an orphaned
     * `*-export-*.zip` behind when the process died mid-export, and delayed the
     * file picker for the whole packing time.
     *
     * The result can be fed back into [importPackage]: `build/` and compile
     * markers are excluded, and a minimal manifest is generated when the
     * workspace has none. Re-importing an exported workspace creates a package
     * whose id comes from its manifest `schema_id` — exporting `Default` and
     * re-importing it yields e.g. `luna_pinyin`, not `Default` (the normal
     * "package id = schema_id" import semantics).
     *
     * [onProgress] receives the number of entry bytes written so far and the
     * total the archive will contain (both are *uncompressed* file bytes, so
     * the ratio is a meaningful percentage). It is called once with zero and
     * once with the final total.
     */
    fun exportPackageTo(
        fileName: String,
        out: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val id = fileName.removeSuffix(".zip")
        PackageMetadata.requireSafePackageId(id)
        exportWorkspaceTo(requireWorkspace(id), id, out, onProgress)
    }

    private fun requireWorkspace(id: String): File {
        val workspace = PackageStore.workspaceDir(id)
        if (!workspace.isDirectory) {
            throw IllegalArgumentException("Package workspace missing: $workspace")
        }
        return workspace
    }

    /**
     * Zip [workspace] into [out], excluding compile artifacts and synthesizing
     * a minimal manifest when the workspace has none. Entries are written in
     * path order so the same workspace always produces the same entry list.
     */
    internal fun exportWorkspaceTo(
        workspace: File,
        id: String,
        out: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val hasManifest =
            File(workspace, "manifest.yaml").isFile ||
                File(workspace, "component.yaml").isFile
        val generatedManifest =
            if (hasManifest) {
                null
            } else {
                val schemaId = PackageMetadata.resolveMinimalManifestSchemaId(workspace, id)
                PackageMetadata.minimalManifest(schemaId).toByteArray(Charsets.UTF_8)
            }
        val entries = workspace
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(workspace).path to it }
            .filterNot { (path, _) -> PackageMetadata.isCompileArtifact(path) }
            .sortedBy { (path, _) -> path }
            .toList()
        val total = entries.sumOf { (_, file) -> file.length() } + (generatedManifest?.size?.toLong() ?: 0L)
        var written = 0L
        onProgress(written, total)
        ZipOutputStream(out.buffered()).use { zipOut ->
            if (generatedManifest != null) {
                zipOut.putNextEntry(ZipEntry("manifest.yaml"))
                zipOut.write(generatedManifest)
                zipOut.closeEntry()
                written += generatedManifest.size
                onProgress(written, total)
            }
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            entries.forEach { (path, file) ->
                zipOut.putNextEntry(ZipEntry(path))
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zipOut.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
                zipOut.closeEntry()
            }
        }
        onProgress(total, total)
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

    /** Buffer for streaming file contents into the zip (progress granularity). */
    private const val COPY_BUFFER_SIZE = 64 * 1024
}
