// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The export is streamed into the destination the user picked, on the UI
 * thread's behalf, so these tests pin the archive content (which files, in
 * which order), the manifest synthesis and the progress contract the export
 * dialog relies on.
 */
class PackageArchiveTest :
    StringSpec({
        fun newWorkspace(): File = Files.createTempDirectory("pkg-archive").toFile()

        fun File.writeRelative(
            path: String,
            content: String,
        ): File = File(this, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

        fun export(workspace: File, id: String = "demo"): ByteArray = ByteArrayOutputStream().also { out ->
            PackageArchive.exportWorkspaceTo(workspace, id, out)
        }.toByteArray()

        /** Entry name -> text content, in the order the entries were written. */
        fun contents(bytes: ByteArray): Map<String, String> {
            val zipFile = File.createTempFile("export", ".zip").apply { writeBytes(bytes) }
            return try {
                ZipFile(zipFile).use { zip ->
                    zip.entries().toList().associate { entry ->
                        entry.name to zip.getInputStream(entry).bufferedReader().readText()
                    }
                }
            } finally {
                zipFile.delete()
            }
        }

        "exports every workspace file but no compile artifacts" {
            val workspace = newWorkspace()
            workspace.writeRelative("manifest.yaml", "name: demo\n")
            workspace.writeRelative("rime/demo.schema.yaml", "schema:\n  schema_id: demo\n")
            workspace.writeRelative("userdb/demo.userdb/db.bin", "db")
            // Compile output and markers belong to the compile flow, not to the
            // package data a user exports.
            workspace.writeRelative("build/demo.table.bin", "compiled")
            workspace.writeRelative("compiled.marker", "deadbeef")
            workspace.writeRelative("compiled.error", "boom")

            contents(export(workspace)).keys.toList() shouldContainExactly
                listOf(
                    "manifest.yaml",
                    "rime/demo.schema.yaml",
                    "userdb/demo.userdb/db.bin",
                )
        }

        "synthesizes a minimal manifest when the workspace has none" {
            val workspace = newWorkspace()
            workspace.writeRelative("rime/luna_pinyin.schema.yaml", "schema:\n  schema_id: luna_pinyin\n")

            val entries = contents(export(workspace, id = "Migrated"))

            entries.keys.toList() shouldContainExactly
                listOf("manifest.yaml", "rime/luna_pinyin.schema.yaml")
            entries.getValue("manifest.yaml") shouldContain "schema_id: luna_pinyin"
        }

        "keeps an existing manifest instead of generating one" {
            val workspace = newWorkspace()
            workspace.writeRelative("manifest.yaml", "name: demo\nschema_id: demo\n")

            contents(export(workspace)).keys.toList() shouldContainExactly listOf("manifest.yaml")
        }

        "reports progress from zero to the total uncompressed size" {
            val workspace = newWorkspace()
            workspace.writeRelative("manifest.yaml", "a".repeat(10_000))
            workspace.writeRelative("rime/data.txt", "b".repeat(5_000))

            val updates = mutableListOf<Pair<Long, Long>>()
            PackageArchive.exportWorkspaceTo(workspace, "demo", ByteArrayOutputStream()) { written, total ->
                updates += written to total
            }

            updates.first() shouldBe (0L to 15_000L)
            updates.last() shouldBe (15_000L to 15_000L)
            updates.forEach { (written, total) ->
                total shouldBe 15_000L
                written shouldBe written.coerceIn(0L, total)
            }
            // A progress bar must never move backwards.
            updates.map { it.first } shouldBe updates.map { it.first }.sorted()
        }

        "writes entries in path order so the same workspace exports the same list" {
            val workspace = newWorkspace()
            workspace.writeRelative("manifest.yaml", "name: demo\n")
            listOf("z.txt", "a/b.txt", "a/a.txt", "m.txt").forEach { workspace.writeRelative(it, it) }

            contents(export(workspace)).keys.toList() shouldBe
                listOf("a/a.txt", "a/b.txt", "m.txt", "manifest.yaml", "z.txt")
        }
    })
