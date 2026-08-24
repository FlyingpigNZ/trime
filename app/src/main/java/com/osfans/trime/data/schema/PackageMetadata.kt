// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Pure parsing/validation helpers for the IME package model: reading package
 * metadata from zips and workspaces, collecting schema ids, and small file
 * utilities (sha256, compile-artifact classification, safe-id checks).
 *
 * No Android or stateful dependencies — split out of [ImePackageManager].
 */
internal object PackageMetadata {
    internal data class ImePackageMeta(
        val fileName: String,
        val packageId: String,
        val name: String,
        val version: String?,
        val schemaId: String,
        val defaultKeyboard: String?,
    )

    fun readPackageMeta(packageFile: File): ImePackageMeta {
        ZipFile(packageFile).use { zip ->
            val node = readManifestNode(zip)
            return ImePackageMeta(
                fileName = packageFile.name,
                packageId = packageFile.nameWithoutExtension,
                name = node["name"]?.string ?: packageFile.nameWithoutExtension,
                version = node["version"]?.string,
                schemaId = node["schema_id"]?.string ?: packageFile.nameWithoutExtension,
                defaultKeyboard = node["default_keyboard"]?.string,
            )
        }
    }

    fun readWorkspaceMeta(workspace: File): ImePackageMeta {
        val manifest =
            listOf(File(workspace, "manifest.yaml"), File(workspace, "component.yaml"))
                .firstOrNull { it.isFile }
                ?: throw IllegalArgumentException("Package workspace is missing manifest.yaml")
        val node = Yaml.Default.parseToYamlNode(manifest.readText(Charsets.UTF_8)).mapping
            ?: throw IllegalArgumentException("Package workspace manifest is not a YAML mapping")
        val schemaId = node["schema_id"]?.string ?: workspace.name
        return ImePackageMeta(
            fileName = "$schemaId.zip",
            packageId = schemaId,
            name = node["name"]?.string ?: schemaId,
            version = node["version"]?.string,
            schemaId = schemaId,
            defaultKeyboard = node["default_keyboard"]?.string,
        )
    }

    private fun readManifestNode(zip: ZipFile): Node.Mapping {
        val entry =
            zip.getEntry("manifest.yaml")
                ?: zip.getEntry("component.yaml")
                ?: throw IllegalArgumentException("IME package is missing manifest.yaml or component.yaml")
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Yaml.Default.parseToYamlNode(text).mapping
            ?: throw IllegalArgumentException("IME package manifest is not a YAML mapping")
    }

    /**
     * Collect schema ids from the package zip itself (after flattening
     * `rime/`). This is the authoritative schema list for the imported package;
     * it must not scan the workspace because overlay imports leave old schemas
     * behind.
     */
    fun schemaIdsFromZip(
        zip: File,
        mainSchemaId: String,
    ): List<String> {
        val ids = mutableListOf<String>()
        ZipFile(zip).use { z ->
            z.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                val relative =
                    if (name.startsWith("rime/")) name.removePrefix("rime/") else name
                if (relative.endsWith(".schema.yaml")) {
                    ids += relative.substringAfterLast('/').removeSuffix(".schema.yaml")
                }
            }
        }
        val distinctIds = ids.distinct()
        if (mainSchemaId !in distinctIds) {
            throw IllegalArgumentException(
                "IME package does not contain $mainSchemaId.schema.yaml",
            )
        }
        return listOf(mainSchemaId) + distinctIds.filter { it != mainSchemaId }
    }

    /**
     * Write `default.custom.yaml` in the workspace with exactly the schemas
     * contained in the imported package. Required for Rime deploy to know which
     * schemas to build.
     */
    fun writeWorkspaceSchemaList(
        workspace: File,
        schemaIds: List<String>,
    ) {
        SchemaListUpdater.setSchemas(File(workspace, "default.custom.yaml"), schemaIds)
    }

    fun minimalManifest(schemaId: String): String =
        """
        name: $schemaId
        schema_id: $schemaId
        default_keyboard: ""
        """.trimIndent()

    /**
     * Pick the schema id for a generated minimal manifest from the workspace
     * itself. Prefer the first entry of `default.custom.yaml`'s `schema_list`;
     * fall back to the first `.schema.yaml` outside `build/`. This keeps
     * export-then-reimport working for manifests workspaces such as `Migrated`,
     * whose directory name is not a real schema id.
     */
    fun resolveMinimalManifestSchemaId(
        workspace: File,
        id: String,
    ): String {
        val custom = File(workspace, "default.custom.yaml")
        if (custom.isFile) {
            runCatching {
                val node =
                    Yaml.Default.parseToYamlNode(custom.readText(Charsets.UTF_8)).mapping
                val schemaList = node?.get("patch")?.mapping?.get("schema_list")?.sequence
                schemaList?.nodes?.firstNotNullOfOrNull { entry ->
                    entry.mapping?.get("schema")?.string
                        ?: entry.string?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
                ?.takeIf { it.isNotBlank() && hasSchemaFile(workspace, it) }
                ?.let { return it }
        }
        val schemaFile =
            workspace.walkTopDown().firstOrNull { file ->
                if (!file.isFile) return@firstOrNull false
                val relative = file.relativeTo(workspace).path
                if (relative == "build" || relative.startsWith("build/")) {
                    return@firstOrNull false
                }
                file.name.endsWith(".schema.yaml")
            }
        if (schemaFile != null) {
            return schemaFile.name.removeSuffix(".schema.yaml")
        }
        throw IllegalArgumentException(
            "Workspace $id has no usable schema; cannot export as an importable package",
        )
    }

    private fun hasSchemaFile(
        workspace: File,
        schemaId: String,
    ): Boolean = workspace.walkTopDown().any { file ->
        if (!file.isFile) return@any false
        val relative = file.relativeTo(workspace).path
        if (relative == "build" || relative.startsWith("build/")) {
            return@any false
        }
        file.name == "$schemaId.schema.yaml"
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Paths inside a workspace that are owned by the compile flow and must
     * never be imported from a package zip or exported as package data.
     */
    fun isCompileArtifact(relativePath: String): Boolean = relativePath == "build" ||
        relativePath.startsWith("build/") ||
        relativePath == "compiled.marker" ||
        relativePath == "compiled.error"

    fun requireSafePackageId(packageId: String) {
        if (!PackageStore.isSafePackageId(packageId)) {
            throw IllegalArgumentException("Unsafe package id: '$packageId'")
        }
    }
}
