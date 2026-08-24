// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Package activation and startup readiness: activating a package (compile if
 * needed, switch the active pointer, restart Rime, restore the theme) and the
 * Default-package bootstrap that guarantees a usable workspace/theme before
 * the UI initializes.
 *
 * Split out of [ImePackageManager].
 */
internal object PackageActivator {
    private val activating = AtomicBoolean(false)
    private val ensureMutex = Mutex()

    /** True while a package is being compiled/activated; input should be blocked. */
    fun isActivating(): Boolean = activating.get()

    /**
     * Activate an imported package. Compiles it first if necessary.
     */
    suspend fun activate(
        packageFile: File,
        applyThemeAfter: Boolean = true,
    ) {
        val id = packageIdFromPackageFile(packageFile)
        PackageMetadata.requireSafePackageId(id)
        if (id == PackageStore.activePackageId() && PackageStore.isCompiled(id)) return
        if (!activating.compareAndSet(false, true)) {
            throw IllegalStateException("IME package activation is already in progress")
        }
        try {
            if (!PackageStore.isCompiled(id)) {
                // activate() performs the final restart/theme restore below, so
                // do not let compilePackage also refresh the active package.
                PackageCompiler.compilePackage(id, restartActive = false)
            }
            PackageStore.setActivePackage(id)
            // Reload Rime with the new package's workspace as user_data_dir.
            RimeDaemon.restartRime()
            if (applyThemeAfter) {
                PackageCompiler.restoreActiveTheme()
            }
        } finally {
            activating.set(false)
        }
    }

    /** Copy the bundled Default.zip into the package library if needed. */
    fun installBundledDefaultPackage() {
        val source = File(DataManager.sharedDataDir, PackageStore.DEFAULT_PACKAGE_FILE_NAME)
        if (!source.isFile) return
        val sourceFingerprint = PackageMetadata.sha256(source)
        val packageDir = PackageStore.packageDir(PackageStore.DEFAULT_PACKAGE_ID).apply { mkdirs() }
        val target = File(packageDir, "package.zip")
        val workspace = PackageStore.defaultWorkspaceDir()
        val marker = File(workspace, "compiled.marker")
        val hasWorkspaceContent =
            File(workspace, "default.yaml").isFile ||
                File(workspace, "manifest.yaml").isFile ||
                File(workspace, "component.yaml").isFile
        if (marker.isFile &&
            marker.readText().trim() == sourceFingerprint &&
            hasWorkspaceContent
        ) {
            return
        }
        // Even without a marker, if the workspace was already extracted from
        // this exact Default.zip, do not wipe and re-extract it (the engine may
        // be deploying it right now).
        if (hasWorkspaceContent && target.isFile && PackageMetadata.sha256(target) == sourceFingerprint) {
            return
        }
        // Source changed or workspace is incomplete: overlay-extract the new
        // Default over the existing workspace without deleting it, so user data
        // that is not part of the package is preserved automatically.
        source.copyTo(target, overwrite = true)
        workspace.mkdirs()
        // Invalidate compile state before overlay extraction.
        marker.delete()
        File(workspace, "compiled.error").delete()
        PackageArchive.extractZipOverlay(target, workspace)
        val schemaId = runCatching { PackageMetadata.readPackageMeta(target).schemaId }.getOrDefault(PackageStore.DEFAULT_PACKAGE_ID)
        val schemaIds = PackageMetadata.schemaIdsFromZip(target, schemaId)
        PackageMetadata.writeWorkspaceSchemaList(workspace, schemaIds)
    }

    /**
     * Compile the workspace the engine will start against — the active
     * package, or Default on a fresh install — via the isolated `:compile`
     * process, before the engine's first start. Runs at application start,
     * ahead of any engine session; the engine's first start is gated on
     * [RimeDaemon.markStartupWorkspaceReady] until this completes, so the main
     * process never runs a full workspace deploy itself.
     */
    suspend fun ensureStartupWorkspaceReady() {
        ensureMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // Sync shared assets first so the bundled Default.zip
                    // exists for [installBundledDefaultPackage].
                    DataManager.sync()
                    installBundledDefaultPackage()
                    val target =
                        PackageStore.activePackageId() ?: PackageStore.DEFAULT_PACKAGE_ID
                    if (!PackageStore.isCompiled(target)) {
                        // restartActive=false: the engine is not started yet
                        // (its first start waits on the gate), so
                        // compilePackage must not restart it.
                        PackageCompiler.compilePackage(
                            target,
                            restartActive = false,
                            restoreThemeAfter = false,
                        )
                    }
                } finally {
                    // Release the engine's first-start gate whether the compile
                    // succeeded or failed: the engine must be allowed to start
                    // either way (a failed deploy surfaces via the FAILED state
                    // and the theme-fallback path takes over).
                    RimeDaemon.markStartupWorkspaceReady()
                }
            }
        }
    }

    /**
     * Ensure a Default package exists and is active before theme/UI init.
     */
    suspend fun ensureDefaultPackageReady() {
        ensureMutex.withLock {
            withContext(Dispatchers.IO) {
                installBundledDefaultPackage()
                val active = PackageStore.activePackageId()
                if (active != null && PackageStore.isCompiled(active)) {
                    try {
                        PackageCompiler.restoreActiveTheme()
                        return@withContext
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Timber.w(t, "Active package theme is unusable; falling back to Default")
                        PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                    }
                } else if (active != null && active != PackageStore.DEFAULT_PACKAGE_ID) {
                    // Active package is unusable; fall back to Default.
                    Timber.w("Active package $active is not compiled; falling back to Default")
                    PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                }
                if (!PackageStore.isCompiled(PackageStore.DEFAULT_PACKAGE_ID)) {
                    // The engine no longer deploys Default in-process at
                    // startup: the startup workspace is compiled by the
                    // isolated :compile process (ensureStartupWorkspaceReady)
                    // before the engine starts, so there is nothing to wait
                    // for here. Compile now if it is still missing.
                    // If we fell back from another active package, the caller
                    // restarts Rime below; only compilePackage itself restarts
                    // when Default was already active and simply needed
                    // compiling. The caller always restores the theme once at
                    // the end, so compilePackage should not restore/notify
                    // here too.
                    PackageCompiler.compilePackage(
                        PackageStore.DEFAULT_PACKAGE_ID,
                        restartActive = active == PackageStore.DEFAULT_PACKAGE_ID,
                        restoreThemeAfter = false,
                    )
                }
                if (PackageStore.activePackageId() == null) {
                    PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                }
                if (active != null && active != PackageStore.DEFAULT_PACKAGE_ID) {
                    RimeDaemon.restartRime()
                }
                try {
                    PackageCompiler.restoreActiveTheme()
                } catch (t: Throwable) {
                    if (active != PackageStore.DEFAULT_PACKAGE_ID) {
                        Timber.w(t, "Active theme restore failed; falling back to Default")
                        PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                        RimeDaemon.restartRime()
                        runCatching { PackageCompiler.restoreActiveTheme() }
                            .onFailure { e ->
                                Timber.e(e, "Default theme unusable; applying builtin fallback theme")
                                PackageCompiler.applyTheme(ThemeManager.fallbackTheme)
                            }
                    } else {
                        // Last resort: never crash the IME at startup. Apply a
                        // builtin minimal theme so the UI stays usable and the
                        // user can repair the package from settings.
                        Timber.e(t, "Default theme unusable; applying builtin fallback theme")
                        PackageCompiler.applyTheme(ThemeManager.fallbackTheme)
                    }
                }
            }
        }
    }

    /** Internal package zips are stored as `<packageDir>/package.zip`. */
    private fun packageIdFromPackageFile(packageFile: File): String = packageFile.parentFile?.name ?: packageFile.nameWithoutExtension
}
