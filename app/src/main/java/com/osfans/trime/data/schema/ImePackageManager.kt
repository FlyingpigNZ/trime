// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import android.content.Intent
import androidx.core.content.ContextCompat
import com.osfans.trime.BuildConfig
import com.osfans.trime.daemon.ImePackageNotifications
import com.osfans.trime.daemon.PackageCompileService
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.component.ComponentThemeLoader
import com.osfans.trime.util.appContext
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * IME package manager for the workspace-pointer package model.
 *
 * Every package owns a complete workspace under [PackageStore.rootDir]; the
 * workspace is used directly as Rime's `user_data_dir`. Importing extracts the
 * zip into the workspace, compiling runs a separate-process deploy and writes
 * `compiled.marker`, and activating only changes the active package pointer.
 */
object ImePackageManager {
    const val DEFAULT_PACKAGE_FILE_NAME = "Default.zip"

    private val activating = AtomicBoolean(false)
    private val compiling = AtomicBoolean(false)
    private val compileMutex = Mutex()
    private val ensureMutex = Mutex()

    data class ImePackage(
        val fileName: String,
        val name: String,
        val version: String?,
        val error: String? = null,
    )

    fun listPackages(): List<ImePackage> =
        PackageStore.rootDir
            .listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir ->
                val id = dir.name
                if (!PackageStore.isSafePackageId(id)) return@mapNotNull null
                if (id == PackageStore.MIGRATED_PACKAGE_ID) return@mapNotNull null
                val zip = File(dir, "package.zip")
                val meta =
                    if (zip.isFile) {
                        runCatching { readPackageMeta(zip) }.getOrNull()
                    } else {
                        runCatching { readWorkspaceMeta(PackageStore.workspaceDir(id)) }.getOrNull()
                    }
                ImePackage(
                    fileName = "$id.zip",
                    name = meta?.name ?: id,
                    version = meta?.version,
                    error = if (meta == null) "Invalid or missing package.zip" else null,
                )
            }
            ?.sortedBy { it.fileName.lowercase() }
            ?: emptyList()

    fun activePackageFileName(): String? = PackageStore.activePackageId()?.let { "$it.zip" }

    fun packageFile(fileName: String): File {
        val id = fileName.removeSuffix(".zip")
        return File(PackageStore.packageDir(id), "package.zip")
    }

    /** Resolve the schema/package id from an imported package zip. */
    fun packageIdOf(packageFile: File): String = readPackageMeta(packageFile).schemaId

    /** Internal package zips are stored as `<packageDir>/package.zip`. */
    private fun packageIdFromPackageFile(packageFile: File): String =
        packageFile.parentFile?.name ?: packageFile.nameWithoutExtension

    fun isDefaultPackage(fileName: String): Boolean = fileName == DEFAULT_PACKAGE_FILE_NAME

    /** Whether the workspace contains a theme the app can actually load. */
    fun hasUsableTheme(workspace: File): Boolean =
        runCatching { loadPackageTheme(workspace) }.isSuccess

    fun isActivePackage(packageFile: File): Boolean =
        packageIdFromPackageFile(packageFile) == PackageStore.activePackageId()

    fun deletePackage(fileName: String): Boolean {
        if (isDefaultPackage(fileName)) return false
        val id = fileName.removeSuffix(".zip")
        if (!PackageStore.isSafePackageId(id)) return false
        val dir = PackageStore.packageDir(id)
        if (!dir.isDirectory) return false
        if (id == PackageStore.activePackageId()) return false
        dir.deleteRecursively()
        return true
    }

    /**
     * Export a workspace as a complete zip snapshot (including user data).
     *
     * The exported zip can be fed back into [importPackage]. `build/` and
     * compile markers are excluded; if the workspace has no manifest, a minimal
     * manifest is generated so the export remains importable.
     *
     * Note: re-importing an exported workspace creates a package whose id comes
     * from its manifest `schema_id`. In particular, exporting `Default` and
     * re-importing it produces a package named e.g. `luna_pinyin`, not
     * `Default`; this is the normal "package id = schema_id" import semantics.
     */
    fun exportPackage(fileName: String): File {
        val id = fileName.removeSuffix(".zip")
        requireSafePackageId(id)
        val workspace = PackageStore.workspaceDir(id)
        if (!workspace.isDirectory) {
            throw IllegalArgumentException("Package workspace missing: $workspace")
        }
        val out = File(workspace.parentFile, "$id-export-${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zipOut ->
                val hasManifest =
                    File(workspace, "manifest.yaml").isFile ||
                        File(workspace, "component.yaml").isFile
                if (!hasManifest) {
                    val schemaId = resolveMinimalManifestSchemaId(workspace, id)
                    zipOut.putNextEntry(ZipEntry("manifest.yaml"))
                    zipOut.write(minimalManifest(schemaId).toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                }
                workspace.walkTopDown().forEach { file ->
                    if (!file.isFile) return@forEach
                    val relative = file.relativeTo(workspace).path
                    if (relative == "build" ||
                        relative.startsWith("build/") ||
                        relative == "compiled.marker" ||
                        relative == "compiled.error"
                    ) {
                        return@forEach
                    }
                    zipOut.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        return out
    }

    private fun minimalManifest(schemaId: String): String =
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
    private fun resolveMinimalManifestSchemaId(
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
    ): Boolean =
        workspace.walkTopDown().any { file ->
            if (!file.isFile) return@any false
            val relative = file.relativeTo(workspace).path
            if (relative == "build" || relative.startsWith("build/")) {
                return@any false
            }
            file.name == "$schemaId.schema.yaml"
        }

    /**
     * Import a package zip into the package library and extract it into its
     * own workspace. This does not compile or activate.
     */
    fun importPackage(source: File): File {
        val meta = readPackageMeta(source)
        requireSafePackageId(meta.schemaId)
        // Validate the package contents before touching the library/workspace.
        // This must happen before any write so a bad package cannot leave a
        // half-imported directory behind.
        val schemaIds = schemaIdsFromZip(source, meta.schemaId)

        val packageDir = PackageStore.packageDir(meta.schemaId)
        val workspace = PackageStore.workspaceDir(meta.schemaId)
        val isNewPackage = !workspace.isDirectory
        try {
            packageDir.mkdirs()
            val zip = File(packageDir, "package.zip")
            source.copyTo(zip, overwrite = true)

            // Overlay-extract into the existing workspace: files owned by the
            // new package overwrite old versions, while files not in the
            // package (user data, user dictionaries, sync, custom resources,
            // etc.) are preserved automatically because we never delete the
            // workspace.
            workspace.mkdirs()
            // Invalidate compile state before overlay extraction: if extraction
            // fails, the workspace must not look compiled.
            File(workspace, "compiled.marker").delete()
            File(workspace, "compiled.error").delete()
            extractZipOverlay(zip, workspace)
            writeWorkspaceSchemaList(workspace, schemaIds)
            return zip
        } catch (t: Throwable) {
            if (isNewPackage) {
                packageDir.deleteRecursively()
            } else {
                // Keep the existing workspace visible, but never let a failed
                // overlay import look like a healthy compiled package.
                File(workspace, "compiled.marker").delete()
                File(workspace, "compiled.error").writeText("import failed\n")
            }
            throw t
        }
    }

    /** Copy the bundled Default.zip into the package library if needed. */
    fun installBundledDefaultPackage() {
        val source = File(DataManager.sharedDataDir, DEFAULT_PACKAGE_FILE_NAME)
        if (!source.isFile) return
        val sourceFingerprint = sha256(source)
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
        if (hasWorkspaceContent && target.isFile && sha256(target) == sourceFingerprint) {
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
        extractZipOverlay(target, workspace)
        val schemaId = runCatching { readPackageMeta(target).schemaId }.getOrDefault(PackageStore.DEFAULT_PACKAGE_ID)
        val schemaIds = schemaIdsFromZip(target, schemaId)
        writeWorkspaceSchemaList(workspace, schemaIds)
    }

    /** Ensure a Default package exists and is active before theme/UI init. */
    suspend fun ensureDefaultPackageReady() {
        ensureMutex.withLock {
            withContext(Dispatchers.IO) {
                installBundledDefaultPackage()
                val active = PackageStore.activePackageId()
                if (active != null && PackageStore.isCompiled(active)) {
                    try {
                        restoreActiveTheme()
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
                    // The engine itself may be deploying Default right now (startup
                    // maintenance). Give it a short window to write compiled.marker
                    // before starting a separate compile service, to avoid two
                    // deploys racing on the same workspace.
                    val defaultWorkspace = PackageStore.defaultWorkspaceDir()
                    val marker = File(defaultWorkspace, "compiled.marker")
                    val error = File(defaultWorkspace, "compiled.error")
                    val waitDeadline = System.currentTimeMillis() + ENGINE_DEPLOY_WAIT_MS
                    while (!marker.isFile && !error.isFile && System.currentTimeMillis() < waitDeadline) {
                        delay(ENGINE_DEPLOY_POLL_INTERVAL_MS)
                    }
                }
                if (!PackageStore.isCompiled(PackageStore.DEFAULT_PACKAGE_ID)) {
                    compilePackage(PackageStore.DEFAULT_PACKAGE_ID)
                }
                if (PackageStore.activePackageId() == null) {
                    PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                }
                if (active != null && active != PackageStore.DEFAULT_PACKAGE_ID) {
                    RimeDaemon.restartRime()
                }
                try {
                    restoreActiveTheme()
                } catch (t: Throwable) {
                    if (active != PackageStore.DEFAULT_PACKAGE_ID) {
                        Timber.w(t, "Active theme restore failed; falling back to Default")
                        PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                        RimeDaemon.restartRime()
                        restoreActiveTheme()
                    } else {
                        throw t
                    }
                }
            }
        }
    }

    fun isCompiled(fileName: String): Boolean =
        PackageStore.isCompiled(fileName.removeSuffix(".zip"))

    /**
     * Mark the current Rime user data dir as compiled. Called when the engine
     * itself finishes a successful deploy (e.g. Default on first startup), so
     * the separate compile service does not redundantly compile it again.
     */
    fun markCurrentWorkspaceCompiled() {
        val workspace = DataManager.userDataDir
        val hasContent =
            File(workspace, "default.yaml").isFile ||
                File(workspace, "manifest.yaml").isFile ||
                File(workspace, "component.yaml").isFile
        if (!hasContent) return
        val active = PackageStore.activePackageId()
        val content =
            if (active == null || active == PackageStore.DEFAULT_PACKAGE_ID) {
                val source = File(DataManager.sharedDataDir, DEFAULT_PACKAGE_FILE_NAME)
                if (source.isFile) sha256(source) else "ok"
            } else {
                "ok"
            }
        File(workspace, "compiled.marker").writeText(content)
        File(workspace, "compiled.error").delete()
    }

    /** Compile an imported package in the background compile process. */
    suspend fun compilePackageFile(fileName: String) {
        val id = fileName.removeSuffix(".zip")
        requireSafePackageId(id)
        compilePackage(id)
    }

    /** Activate an imported package. Compiles it first if necessary. */
    suspend fun activate(
        packageFile: File,
        applyThemeAfter: Boolean = true,
    ) {
        val id = packageIdFromPackageFile(packageFile)
        requireSafePackageId(id)
        if (id == PackageStore.activePackageId() && PackageStore.isCompiled(id)) return
        if (!activating.compareAndSet(false, true)) {
            throw IllegalStateException("IME package activation is already in progress")
        }
        try {
            if (!PackageStore.isCompiled(id)) {
                compilePackage(id)
            }
            PackageStore.setActivePackage(id)
            // Reload Rime with the new package's workspace as user_data_dir.
            RimeDaemon.restartRime()
            if (applyThemeAfter) {
                restoreActiveTheme()
            }
        } finally {
            activating.set(false)
        }
    }

    /** True while a package is being compiled/activated; input should be blocked. */
    fun isActivating(): Boolean = activating.get()

    /** True while a compile/activation is running; UI should block new imports. */
    fun isBusy(): Boolean = activating.get() || compiling.get()

    /** Registry view of the active package's schema → keyboard binding. */
    fun registry(): DefaultKeyboardRegistry {
        val workspace = PackageStore.activeWorkspaceDir() ?: return DefaultKeyboardRegistry.Empty
        val manifest = listOf(File(workspace, "manifest.yaml"), File(workspace, "component.yaml"))
            .firstOrNull { it.isFile }
            ?: return DefaultKeyboardRegistry.Empty
        val node = runCatching {
            Yaml.Default.parseToYamlNode(manifest.readText(Charsets.UTF_8)).mapping
        }.getOrNull() ?: return DefaultKeyboardRegistry.Empty
        val schemaId = node["schema_id"]?.string ?: return DefaultKeyboardRegistry.Empty
        val defaultKeyboard = node["default_keyboard"]?.string ?: return DefaultKeyboardRegistry.Empty
        return DefaultKeyboardRegistry.fromDefaultKeyboards(mapOf(schemaId to defaultKeyboard))
    }

    fun defaultKeyboardFor(schemaId: String): String? = registry().defaultKeyboardFor(schemaId)

    /** Reload the active package's theme after startup or activation. */
    suspend fun restoreActiveTheme() {
        val workspace = withContext(Dispatchers.IO) { PackageStore.activeWorkspaceDir() } ?: return
        val theme =
            withContext(Dispatchers.IO) {
                loadPackageTheme(workspace)
            }
        applyTheme(theme)
    }

    /**
     * Compile a package workspace in the separate `:compile` process and wait
     * for `compiled.marker`.
     */
    private suspend fun compilePackage(packageId: String) {
        compileMutex.withLock {
            val workspace = PackageStore.workspaceDir(packageId)
            if (!workspace.isDirectory) {
                throw IllegalArgumentException("Package workspace missing: $workspace")
            }
            if (PackageStore.isCompiled(packageId)) return@withLock
            if (!compiling.compareAndSet(false, true)) {
                throw IllegalStateException("IME package compile is already in progress")
            }
            try {
                val marker = File(workspace, "compiled.marker")
                val error = File(workspace, "compiled.error")
                marker.delete()
                error.delete()
                val intent =
                    Intent(appContext, PackageCompileService::class.java).apply {
                        putExtra(PackageCompileService.EXTRA_WORKSPACE_DIR, workspace.absolutePath)
                        putExtra(
                            PackageCompileService.EXTRA_SHARED_DIR,
                            DataManager.sharedDataDir.absolutePath,
                        )
                        putExtra(PackageCompileService.EXTRA_VERSION, BuildConfig.BUILD_VERSION_NAME)
                    }
                ContextCompat.startForegroundService(appContext, intent)
                val deadline = System.currentTimeMillis() + COMPILE_TIMEOUT_MS
                while (!marker.isFile && !error.isFile && System.currentTimeMillis() < deadline) {
                    delay(COMPILE_POLL_INTERVAL_MS)
                }
                if (error.isFile) {
                    throw IllegalStateException("IME package compile failed: $packageId")
                }
                if (!marker.isFile) {
                    throw IllegalStateException("IME package compile timed out: $packageId")
                }
                // If the active package was recompiled in place, restart Rime so it
                // picks up the new workspace contents, and reload the theme so the
                // in-memory keyboard layout reflects the updated package immediately.
                if (PackageStore.activePackageId() == packageId) {
                    RimeDaemon.restartRime()
                    restoreActiveTheme()
                    ImePackageNotifications.notifyRefreshed(appContext)
                }
            } finally {
                compiling.set(false)
            }
        }
    }

    /**
     * Overlay-extract [zip] into [workspace] without deleting existing files.
     * Package files overwrite old versions; files not present in the zip are
     * left untouched, which preserves user data across re-import/update.
     */
    private fun extractZipOverlay(
        zip: File,
        workspace: File,
    ) {
        ZipFile(zip).use { z ->
            z.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                val normalizedName = File(name).toPath().normalize()
                if (normalizedName.isAbsolute || normalizedName.startsWith("..")) {
                    throw IllegalArgumentException("Unsafe path in IME package: $name")
                }
                val relative =
                    if (name.startsWith("rime/")) name.removePrefix("rime/") else name
                val target = File(workspace, relative)
                val normalizedTarget = target.toPath().toAbsolutePath().normalize()
                if (!normalizedTarget.startsWith(workspace.toPath().toAbsolutePath().normalize())) {
                    throw IllegalArgumentException("Unsafe path in IME package: $name")
                }
                target.parentFile?.mkdirs()
                z.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * Collect schema ids from the package zip itself (after flattening
     * `rime/`). This is the authoritative schema list for the imported package;
     * it must not scan the workspace because overlay imports leave old schemas
     * behind.
     */
    private fun schemaIdsFromZip(
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
    private fun writeWorkspaceSchemaList(
        workspace: File,
        schemaIds: List<String>,
    ) {
        SchemaListUpdater.setSchemas(File(workspace, "default.custom.yaml"), schemaIds)
    }

    private fun readWorkspaceMeta(workspace: File): ImePackageMeta {
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

    private fun readPackageMeta(packageFile: File): ImePackageMeta {
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

    private fun readManifestNode(zip: ZipFile): Node.Mapping {
        val entry =
            zip.getEntry("manifest.yaml")
                ?: zip.getEntry("component.yaml")
                ?: throw IllegalArgumentException("IME package is missing manifest.yaml or component.yaml")
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Yaml.Default.parseToYamlNode(text).mapping
            ?: throw IllegalArgumentException("IME package manifest is not a YAML mapping")
    }

    private fun loadPackageTheme(workspace: File): Theme {
        val componentManifest =
            listOf(File(workspace, "component.yaml"), File(workspace, "manifest.yaml"))
                .firstOrNull { it.isFile && ComponentThemeLoader.isComponentManifest(it) }
        if (componentManifest != null) {
            return ComponentThemeLoader.loadTheme(componentManifest)
        }
        val themeFile = File(workspace, "theme.yaml")
        if (themeFile.isFile) {
            val node = Yaml.Default.parseToYamlNode(themeFile.readText(Charsets.UTF_8)).mapping
                ?: throw IllegalArgumentException("theme.yaml is not a mapping")
            val style = node["style"]?.mapping
            val hasColorSchemes =
                node["preset_color_schemes"]?.mapping?.pairs?.isNotEmpty() == true ||
                    node["color_schemes"]?.mapping?.pairs?.isNotEmpty() == true
            if (style == null || style.pairs.isEmpty()) {
                throw IllegalArgumentException("theme.yaml must define a non-empty 'style' section")
            }
            if (!hasColorSchemes) {
                throw IllegalArgumentException("theme.yaml must define at least one color scheme")
            }
            val name = node["name"]?.string ?: workspace.name
            return Theme.decode(
                Node.Mapping(
                    LinkedHashMap(node.pairs).apply {
                        put(Node.Scalar("name"), Node.Scalar(name))
                    },
                ),
            )
        }
        throw IllegalArgumentException("IME package has no component manifest or theme.yaml")
    }

    private fun sha256(file: File): String {
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

    private fun requireSafePackageId(packageId: String) {
        if (!PackageStore.isSafePackageId(packageId)) {
            throw IllegalArgumentException("Unsafe package id: '$packageId'")
        }
    }

    private suspend fun applyTheme(theme: Theme) {
        withContext(Dispatchers.Main) {
            ThemeManager.applySchemaLayout(theme, replaceTheme = true)
        }
    }

    private data class ImePackageMeta(
        val fileName: String,
        val packageId: String,
        val name: String,
        val version: String?,
        val schemaId: String,
        val defaultKeyboard: String?,
    )

    private const val COMPILE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val COMPILE_POLL_INTERVAL_MS = 500L
    private const val ENGINE_DEPLOY_WAIT_MS = 30 * 1000L
    private const val ENGINE_DEPLOY_POLL_INTERVAL_MS = 200L
}
