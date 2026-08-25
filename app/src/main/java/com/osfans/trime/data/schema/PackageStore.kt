// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

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
}
