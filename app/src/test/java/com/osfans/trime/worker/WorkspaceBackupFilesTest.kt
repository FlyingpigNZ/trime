/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.worker

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WorkspaceBackupFilesTest :
    StringSpec({
        "fileName embeds prefix, package id and a fixed-width UTC timestamp" {
            val name = WorkspaceBackupFiles.fileName("Default", 1_767_225_600_000L)
            name shouldBe "trime-backup-Default-20260101-000000.zip"
            WorkspaceBackupFiles.isBackupOf("Default", name) shouldBe true
        }

        "isBackupOf rejects other packages, sibling prefixes and foreign files" {
            WorkspaceBackupFiles.isBackupOf("Default", "trime-backup-Other-20260101-000000.zip") shouldBe
                false
            WorkspaceBackupFiles.isBackupOf("Default", "trime-backup-Default2-20260101-000000.zip") shouldBe
                false
            WorkspaceBackupFiles.isBackupOf("Default", "trime-backup-Default-20260101-000000.txt") shouldBe
                false
            WorkspaceBackupFiles.isBackupOf("Default", "notes.txt") shouldBe false
        }

        "namesToPrune keeps the newest copies and returns only stale ones" {
            val names =
                listOf(
                    "trime-backup-Default-20260101-000000.zip",
                    "trime-backup-Default-20260102-000000.zip",
                    "trime-backup-Default-20260103-000000.zip",
                    "trime-backup-Default-20260104-000000.zip",
                    "trime-backup-Default-20260105-000000.zip",
                )
            WorkspaceBackupFiles.namesToPrune(names, "Default", 3) shouldBe
                listOf(
                    "trime-backup-Default-20260102-000000.zip",
                    "trime-backup-Default-20260101-000000.zip",
                )
            WorkspaceBackupFiles.namesToPrune(names, "Default", 5) shouldBe emptyList()
            WorkspaceBackupFiles.namesToPrune(names, "Default", 1) shouldBe
                listOf(
                    "trime-backup-Default-20260104-000000.zip",
                    "trime-backup-Default-20260103-000000.zip",
                    "trime-backup-Default-20260102-000000.zip",
                    "trime-backup-Default-20260101-000000.zip",
                )
        }

        "namesToPrune ignores unrelated files and defensively prunes nothing" {
            val names =
                listOf(
                    "notes.txt",
                    "trime-backup-Other-20260101-000000.zip",
                    "trime-backup-Default2-20260101-000000.zip",
                    "trime-backup-Default-20260101-000000.zip",
                    "trime-backup-Default-20260102-000000.zip",
                )
            WorkspaceBackupFiles.namesToPrune(names, "Default", 1) shouldBe
                listOf("trime-backup-Default-20260101-000000.zip")
            WorkspaceBackupFiles.namesToPrune(names, "Default", 0) shouldBe emptyList()
        }
    })
