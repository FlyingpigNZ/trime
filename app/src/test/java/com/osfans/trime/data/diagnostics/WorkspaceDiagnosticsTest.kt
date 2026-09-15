// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.diagnostics

import com.osfans.trime.data.diagnostics.WorkspaceDiagnostics.DirSnapshot
import com.osfans.trime.data.diagnostics.WorkspaceDiagnostics.Entry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class WorkspaceDiagnosticsTest :
    StringSpec({
        "a missing baseline marks everything as newer" {
            WorkspaceDiagnostics.isNewerThanBaseline(mtimeMs = 1_000L, lastBuildTimeSec = null) shouldBe true
        }

        "an mtime in the same second is not newer, matching librime's whole-second comparison" {
            WorkspaceDiagnostics.isNewerThanBaseline(
                mtimeMs = 1_700_000_000_999L,
                lastBuildTimeSec = 1_700_000_000L,
            ) shouldBe false
        }

        "an mtime in the next second is newer" {
            WorkspaceDiagnostics.isNewerThanBaseline(
                mtimeMs = 1_700_000_001_000L,
                lastBuildTimeSec = 1_700_000_000L,
            ) shouldBe true
        }

        "describeDir separates newer entries from actual trigger candidates" {
            val snapshot =
                DirSnapshot(
                    path = "/ws",
                    mtimeMs = 1_700_000_010_000L,
                    entries =
                    listOf(
                        Entry("installation.yaml", false, 1_700_000_010_000L),
                        Entry("compiled.marker", false, 1_700_000_009_000L),
                        Entry("luna_pinyin.userdb", true, 1_700_000_008_000L),
                        Entry("user.yaml", false, 1_699_999_990_000L),
                    ),
                )
            val lines = WorkspaceDiagnostics.describeDir("ws", snapshot, 1_700_000_000L)
            lines.first() shouldContain "newer=3"
            lines.first() shouldContain "triggers=1"
            lines.first() shouldContain "dirNewer=true"
            lines.any { it.contains("installation.yaml") && it.contains("TRIGGER") } shouldBe true
            // Non-yaml files and directory mtimes are never read by librime.
            lines.any { it.contains("compiled.marker") && it.contains("TRIGGER") } shouldBe false
            lines.any { it.contains("luna_pinyin.userdb") && it.contains("TRIGGER") } shouldBe false
            lines.any { it.contains("user.yaml") && it.contains("TRIGGER") } shouldBe false
        }

        "only top-level yaml files other than user.yaml are trigger candidates" {
            WorkspaceDiagnostics.isTriggerCandidate(Entry("installation.yaml", false, 0L)) shouldBe true
            WorkspaceDiagnostics.isTriggerCandidate(Entry("default.custom.yaml", false, 0L)) shouldBe true
            WorkspaceDiagnostics.isTriggerCandidate(Entry("user.yaml", false, 0L)) shouldBe false
            WorkspaceDiagnostics.isTriggerCandidate(Entry("compiled.marker", false, 0L)) shouldBe false
            WorkspaceDiagnostics.isTriggerCandidate(Entry("luna_pinyin.userdb", true, 0L)) shouldBe false
        }

        "describeDir truncates the rendered entries to the report limit" {
            val omitted = 5
            val entries =
                (1..(WorkspaceDiagnostics.MAX_REPORTED_ENTRIES + omitted)).map {
                    Entry("f$it", false, it.toLong())
                }
            val lines =
                WorkspaceDiagnostics.describeDir(
                    "ws",
                    DirSnapshot("/ws", 0L, entries),
                    lastBuildTimeSec = null,
                )
            lines.size shouldBe 1 + WorkspaceDiagnostics.MAX_REPORTED_ENTRIES + 1
            lines.last() shouldContain "$omitted more entries omitted"
        }

        "report covers both directories, the baseline and the trigger rule" {
            val cause =
                WorkspaceDiagnostics.DeployCause(
                    workspace = DirSnapshot("/ws", 0L, emptyList()),
                    shared = DirSnapshot("/sh", 0L, emptyList()),
                    lastBuildTimeSec = 42L,
                )
            val text = WorkspaceDiagnostics.report(cause).joinToString("\n")
            text shouldContain "baseline last_build_time=42"
            text shouldContain "rule: root dir mtime, or top-level *.yaml except user.yaml"
            text shouldContain "ws: path=/ws"
            text shouldContain "sh: path=/sh"
        }
    })
