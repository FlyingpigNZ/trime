// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SchemaLayoutPackageInstallerTest :
    StringSpec({
        "install extracts schema, layout, and resources" {
            val packageFile = createPackage(includeManifest = true)
            val destDir = Files.createTempDirectory("schema-dest").toFile()

            val manifest = SchemaLayoutPackageInstaller.install(packageFile, destDir)

            manifest.schemaId shouldBe "14jian"
            manifest.resources shouldBe listOf("backgrounds/14jian.png")
            File(destDir, "14jian/14jian.schema.yaml").exists() shouldBe true
            File(destDir, "14jian/14jian.layout.yaml").exists() shouldBe true
            File(destDir, "14jian/backgrounds/14jian.png").exists() shouldBe true
        }

        "install fails when manifest is missing" {
            val packageFile = createPackage(includeManifest = false)
            val destDir = Files.createTempDirectory("schema-dest").toFile()

            shouldThrow<IllegalArgumentException> {
                SchemaLayoutPackageInstaller.install(packageFile, destDir)
            }
        }

        "install rejects zip-slip paths" {
            val packageFile = File(Files.createTempDirectory("schema-pkg").toFile(), "bad.zip")
            ZipOutputStream(packageFile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.yaml"))
                zip.write(MANIFEST.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("../evil.txt"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
            val destDir = Files.createTempDirectory("schema-dest").toFile()

            shouldThrow<IllegalArgumentException> {
                SchemaLayoutPackageInstaller.install(packageFile, destDir)
            }
        }
    })

private fun createPackage(includeManifest: Boolean): File {
    val packageFile = File(Files.createTempDirectory("schema-pkg").toFile(), "14jian.zip")
    ZipOutputStream(packageFile.outputStream()).use { zip ->
        if (includeManifest) {
            zip.putNextEntry(ZipEntry("manifest.yaml"))
            zip.write(MANIFEST.toByteArray())
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("14jian.schema.yaml"))
        zip.write("schema:\n  schema_id: 14jian\n".toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("14jian.layout.yaml"))
        zip.write("name: 14键布局\nstyle: {}\n".toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("backgrounds/14jian.png"))
        zip.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        zip.closeEntry()
    }
    return packageFile
}

private val MANIFEST =
    """
    schema_id: 14jian
    name: 小鹤双拼14键
    version: "0.1"
    schema_file: 14jian.schema.yaml
    layout_files:
      - 14jian.layout.yaml
    default_keyboard: 14jian
    resources:
      - backgrounds/14jian.png
    """.trimIndent()
