// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import android.os.Process
import com.osfans.trime.util.appContext
import java.io.File
import java.io.FileOutputStream

/**
 * Storage layout for the self-contained IME package model.
 *
 * Each package owns a complete [workspace] directory that can serve directly
 * as Rime's `user_data_dir`. Switching packages only changes the active
 * pointer; the workspace itself is never copied into a shared `/rime` dir.
 *
 * ```
 * files/                          # app-managed external files
 * ├── shared/                     # read-only shared Rime data
 * └── packages/
 *     ├── <packageId>/
 *     │   ├── package.zip
 *     │   └── workspace/          # full user_data_dir for this package
 *     └── ...
 * ```
 */
object PackageStore {
    const val DEFAULT_PACKAGE_ID = "Default"
    const val MIGRATED_PACKAGE_ID = "Migrated"

    /** Written by the `:compile` process with its pid; lets the main process
     *  detect compile-process death via /proc instead of waiting out the
     *  compile timeout. */
    const val COMPILE_PID_FILE = "compile.pid"

    /** mtime heartbeat refreshed by the `:compile` process while alive;
     *  fallback liveness signal when the pid file is missing/unreadable. */
    const val COMPILE_HEARTBEAT_FILE = "compile.heartbeat"

    /** Bundled Default package zip shipped with the app. */
    const val DEFAULT_PACKAGE_FILE_NAME = "Default.zip"

    private val externalFilesDir: File?
        get() = appContext.getExternalFilesDir(null)

    /** App-managed package library root. */
    val rootDir: File
        get() = File(externalFilesDir, "packages").apply { mkdirs() }

    /** Internal pointer file; kept outside the package library. */
    private val activePointerFile: File
        get() = File(appContext.filesDir, "active-package")

    fun packageDir(packageId: String): File = File(rootDir, packageId)

    fun workspaceDir(packageId: String): File = File(packageDir(packageId), "workspace")

    fun defaultWorkspaceDir(): File = workspaceDir(DEFAULT_PACKAGE_ID)

    fun activePackageId(): String? = activePointerFile
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && isSafePackageId(it) }

    fun activeWorkspaceDir(): File? = activePackageId()?.let(::workspaceDir)

    /**
     * Atomically switch the active package. Write to a temp file and rename so
     * a crash cannot leave a truncated pointer.
     */
    fun setActivePackage(packageId: String) {
        require(isSafePackageId(packageId)) { "Unsafe package id: $packageId" }
        activePointerFile.parentFile?.mkdirs()
        // Unique temp name: a fixed .tmp would let two concurrent writers
        // tear each other's file.
        val tmp = File(activePointerFile.parentFile, "${activePointerFile.name}.${System.nanoTime()}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(packageId.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(activePointerFile)) {
            activePointerFile.writeText(packageId)
            tmp.delete()
        }
    }

    fun isSafePackageId(packageId: String): Boolean = packageId.isNotEmpty() &&
        packageId != "." &&
        packageId != ".." &&
        packageId.matches(Regex("[A-Za-z0-9._-]+"))

    fun isCompiled(packageId: String): Boolean = File(workspaceDir(packageId), "compiled.marker").isFile

    /**
     * Identity of a compile session: the pid of the `:compile` process plus
     * its /proc start time (when known). The start time lets the main process
     * tell "the same session is still running" from "the pid was recycled by
     * an unrelated process", so it never force-kills a stranger.
     */
    data class CompileSessionRef(val pid: Int, val startTime: Long?)

    /**
     * Record this process's pid (and start time) as the compile session of
     * [workspace]. The main process reads it back to enforce "one compile
     * session at a time, and only after the previous one fully exited".
     *
     * Written atomically (temp + rename): the main process polls this file
     * every 500ms, and a non-atomic write can be read mid-write as a
     * truncated pid/start time, which the liveness check would misread as a
     * dead session.
     */
    fun writeCompileSessionRef(workspace: File) {
        val pid = Process.myPid()
        val startTime = processStartTime(pid)
        val content = if (startTime != null) "$pid $startTime" else pid.toString()
        val pidFile = File(workspace, COMPILE_PID_FILE)
        val tmp = File(workspace, "$COMPILE_PID_FILE.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(pidFile)) {
            pidFile.writeText(content)
            tmp.delete()
        }
    }

    fun readCompileSessionRef(workspace: File): CompileSessionRef? {
        val text =
            runCatching { File(workspace, COMPILE_PID_FILE).readText().trim() }
                .getOrNull() ?: return null
        // A malformed pid file (mid-write in an old build, or a stray file)
        // must read as "no session yet" rather than a session whose pid does
        // not exist: declaring death from garbage is a false "compile died".
        if (!text.matches(Regex("\\d+( \\d+)?"))) return null
        val parts = text.split(' ')
        val pid = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val startTime = parts.getOrNull(1)?.toLongOrNull()
        return CompileSessionRef(pid, startTime)
    }

    /**
     * Whether the recorded compile session is still the same live process.
     * `/proc/<pid>` existence alone can false-positive when a dead session's
     * pid is recycled, so when a start time was recorded it must match
     * `/proc/<pid>/stat` field 22; unreadable/missing start times fall back to
     * mere pid existence (never report a live process as dead over a missing
     * start time).
     */
    fun isCompileSessionAlive(ref: CompileSessionRef): Boolean {
        if (!File("/proc/${ref.pid}/stat").isFile) return false
        val recordedStart = ref.startTime ?: return true
        val currentStart = processStartTime(ref.pid) ?: return true
        return recordedStart == currentStart
    }

    /**
     * Field 22 (`starttime`) of `/proc/<pid>/stat`, in clock ticks since boot.
     * `/proc/<pid>/stat` is `pid (comm) state ppid ... starttime ...`; `comm`
     * (field 2) may contain spaces, so tokens are parsed after the last `)`.
     * `starttime` is the 22nd field overall, i.e. the 19th token (0-based)
     * after `comm`'s closing parenthesis.
     */
    fun processStartTime(pid: Int): Long? =
        runCatching {
            val stat = File("/proc/$pid/stat").readText()
            val afterComm = stat.substringAfterLast(')')
            afterComm.trim().split(Regex("\\s+"))[PROC_STAT_START_TIME_INDEX].toLong()
        }.getOrNull()

    private const val PROC_STAT_START_TIME_INDEX = 19
}
