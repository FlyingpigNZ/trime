// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DiagnosticLogTest :
    StringSpec({
        "formatLine keeps timestamp, level, pid, thread, tag and message" {
            DiagnosticLog.formatLine("T", 42, "main", 'I', "tag", "msg") shouldBe "T I [42 main] tag: msg"
        }

        "classifies a previous run that crashed" {
            val lines =
                listOf(
                    logLine("=== process start pid=1 process=com.osfans.trime"),
                    logLine("!!! crash java.lang.IllegalStateException: boom"),
                    logLine("=== process exit"),
                )
            DiagnosticLog.classifyPreviousRun(lines, "com.osfans.trime") shouldBe DiagnosticLog.PreviousRun.CRASH
        }

        "classifies a previous run that exited cleanly" {
            val lines =
                listOf(
                    logLine("=== process start pid=1 process=com.osfans.trime"),
                    logLine("=== process exit"),
                )
            DiagnosticLog.classifyPreviousRun(lines, "com.osfans.trime") shouldBe DiagnosticLog.PreviousRun.CLEAN_EXIT
        }

        "classifies a previous run with no exit record as killed" {
            val lines =
                listOf(
                    logLine("=== process start pid=1 process=com.osfans.trime"),
                    logLine("engine: state=READY"),
                )
            DiagnosticLog.classifyPreviousRun(lines, "com.osfans.trime") shouldBe DiagnosticLog.PreviousRun.UNKNOWN_KILLED
        }

        "returns NONE when this process has no earlier start record" {
            val lines = listOf(logLine("=== process start pid=1 process=com.osfans.trime:compile"))
            DiagnosticLog.classifyPreviousRun(lines, "com.osfans.trime") shouldBe DiagnosticLog.PreviousRun.NONE
        }

        "ignores a later start of another process" {
            val lines =
                listOf(
                    logLine("=== process start pid=1 process=com.osfans.trime"),
                    logLine("=== process exit"),
                    logLine("=== process start pid=2 process=com.osfans.trime:compile"),
                )
            DiagnosticLog.classifyPreviousRun(lines, "com.osfans.trime") shouldBe DiagnosticLog.PreviousRun.CLEAN_EXIT
        }
    })

private fun logLine(message: String) = "2026-01-01T00:00:00.000+0000 I [1 main] test: $message"
