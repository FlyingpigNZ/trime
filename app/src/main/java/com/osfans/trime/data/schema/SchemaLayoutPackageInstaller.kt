// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import java.io.File
import java.util.zip.ZipFile

/**
 * Unpacks a tier-3 schema-layout package (zip) into an install directory.
 *
 * Package layout:
 * ```
 * manifest.yaml
 * <schema>.schema.yaml
 * <layout>.layout.yaml
 * resources/...   (optional background images, fonts, etc.)
 * ```
 *
 * The returned manifest is parsed from the package; callers can then compile
 * the schema through Rime and merge the layout files into the runtime theme.
 */
object SchemaLayoutPackageInstaller {
    fun install(
        packageFile: File,
        destDir: File,
    ): SchemaLayoutManifest {
        if (!packageFile.isFile) {
            throw IllegalArgumentException("Schema layout package not found: $packageFile")
        }
        ZipFile(packageFile).use { zip ->
            val manifestEntry = zip.getEntry("manifest.yaml")
                ?: throw IllegalArgumentException("Package is missing manifest.yaml: $packageFile")
            val manifestText =
                zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            val manifest = SchemaLayoutManifest.parse(manifestText)

            // Validate referenced files exist and layout files parse as YAML.
            (listOf(manifest.schemaFile) + manifest.layoutFiles + manifest.resources).forEach { name ->
                if (zip.getEntry(name) == null) {
                    throw IllegalArgumentException("Package is missing referenced file: $name")
                }
            }
            manifest.layoutFiles.forEach { name ->
                val text = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
                if (Yaml.Default.parseToYamlNode(text).mapping == null) {
                    throw IllegalArgumentException("Layout file is not a YAML mapping: $name")
                }
            }

            val targetDir = File(destDir, manifest.schemaId).apply { mkdirs() }
            val targetPath = targetDir.toPath().toAbsolutePath().normalize()

            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val outputFile = File(targetDir, entry.name).toPath().toAbsolutePath().normalize().toFile()
                if (!outputFile.toPath().startsWith(targetPath)) {
                    throw IllegalArgumentException(
                        "Unsafe path in schema layout package: ${entry.name}",
                    )
                }
                outputFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return manifest
        }
    }
}
