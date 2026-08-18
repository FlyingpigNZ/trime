// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import android.os.Handler
import android.os.Looper
import com.osfans.trime.core.Rime
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.component.ComponentThemeLoader
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml as SnakeYaml
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * IME package manager for the self-contained package model.
 *
 * Packages live in the app-managed Rime user data dir under `IMEs/` as zip
 * files. Activating a package extracts its `rime/...` files into the Rime user
 * data dir, extracts its definition files into a persistent per-package state
 * dir under `IMEs/`, deploys the Rime schemas, and writes an active manifest
 * that doubles as the uninstall manifest.
 */
object ImePackageManager {
    const val DEFAULT_PACKAGE_FILE_NAME = "Default.zip"

    private val activating = AtomicBoolean(false)
    private val activationLock = Any()
    private val ensureMutex = Mutex()

    private val imesDir: File
        get() = File(DataManager.userDataDir, "IMEs").apply { mkdirs() }

    private val activeManifestFile: File
        get() = File(imesDir, "active-manifest.yaml")

    private val buildDir: File
        get() = DataManager.stagingDir

    data class ImePackage(
        val fileName: String,
        val name: String,
        val version: String?,
        val error: String? = null,
    )

    fun listPackages(): List<ImePackage> {
        imesDir.mkdirs()
        return imesDir.listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { file ->
                runCatching { readPackageMeta(file) }.fold(
                    onSuccess = { meta -> ImePackage(meta.fileName, meta.name, meta.version) },
                    onFailure = { e ->
                        Timber.w("Failed to read IME package ${file.name}: ${e.message}")
                        ImePackage(
                            fileName = file.name,
                            name = file.nameWithoutExtension,
                            version = null,
                            error = e.message ?: "Invalid IME package",
                        )
                    },
                )
            }
            ?: emptyList()
    }

    fun activePackageFileName(): String? =
        activeManifestFile
            .takeIf { it.isFile }
            ?.let { readActiveManifest(it)["active_package"] as? String }

    fun packageFile(fileName: String): File = File(imesDir, fileName)

    /**
     * Install the application-delivered Default.zip from the synced shared data
     * dir into the persistent app-managed IME package library. No-op when the
     * bundled package is already up to date.
     */
    fun installBundledDefaultPackage() {
        val source = File(DataManager.sharedDataDir, DEFAULT_PACKAGE_FILE_NAME)
        if (!source.isFile) return
        val target = File(imesDir, DEFAULT_PACKAGE_FILE_NAME)
        if (target.isFile && sha256(target) == sha256(source)) return
        source.copyTo(target, overwrite = true)
    }

    /**
     * Make sure an IME package is active before any theme-dependent UI is
     * created. If the app-managed IME library already has an active manifest,
     * restore its theme. Otherwise install and activate the
     * application-delivered Default.zip.
     */
    suspend fun ensureDefaultPackageReady() {
        ensureMutex.withLock {
            withContext(Dispatchers.IO) { cleanupInterruptedActivation() }
            if (activeManifestFile.isFile) {
                restoreActiveTheme()
                return
            }
            installBundledDefaultPackage()
            val defaultPackage = packageFile(DEFAULT_PACKAGE_FILE_NAME)
            if (!defaultPackage.isFile) {
                Timber.w("Bundled Default.zip is missing; no IME package can be activated")
                return
            }
            withContext(Dispatchers.IO) {
                activate(defaultPackage, applyThemeAfter = false)
            }
            // activate() writes the active manifest on the IO thread; restore now on
            // the main thread so the package theme is available synchronously.
            restoreActiveTheme()
        }
    }

    /** Registry view of the active IME package's explicit schema→keyboard binding. */
    fun registry(): DefaultKeyboardRegistry {
        val active = activeManifestFile
        if (!active.isFile) return DefaultKeyboardRegistry.Empty
        val data = readActiveManifest(active)
        val schemaId = data["schema_id"] as? String ?: return DefaultKeyboardRegistry.Empty
        val defaultKeyboard = data["default_keyboard"] as? String ?: return DefaultKeyboardRegistry.Empty
        return DefaultKeyboardRegistry.fromDefaultKeyboards(mapOf(schemaId to defaultKeyboard))
    }

    /** The active IME package's explicit default keyboard for [schemaId], if any. */
    fun defaultKeyboardFor(schemaId: String): String? = registry().defaultKeyboardFor(schemaId)

    /**
     * True when [packageFile] is the exact package currently active (same
     * content fingerprint), so re-activating it would be a no-op.
     */
    fun isActivePackage(packageFile: File): Boolean {
        val active = activeManifestFile
        if (!active.isFile) return false
        val storedSha = readActiveManifest(active)["package_sha256"] as? String ?: return false
        return storedSha == sha256(packageFile)
    }

    /**
     * Restore the active package's theme after app startup. The extracted files
     * and compiled Rime data already exist in the app-managed Rime data dir;
     * this only reloads the package's component manifest and applies it as the
     * active theme.
     */
    suspend fun restoreActiveTheme() {
        val active = activeManifestFile
        if (!active.isFile) return
        val data = readActiveManifest(active)
        val packageId = data["package_id"] as? String
        @Suppress("UNCHECKED_CAST")
        val staleRimeFiles = (data["rime_files"] as? List<String>) ?: emptyList()
        val stateDir = packageId?.let { File(imesDir, it) }
        val componentManifest =
            if (stateDir != null) {
                listOf(File(stateDir, "component.yaml"), File(stateDir, "manifest.yaml"))
                    .firstOrNull { it.isFile && ComponentThemeLoader.isComponentManifest(it) }
            } else {
                null
            }
        if (packageId == null || componentManifest == null) {
            Timber.w("Active IME package manifest is unusable; falling back to Default.zip")
            healStaleActiveManifest(packageId, staleRimeFiles)
            return
        }
        try {
            (data["schema_id"] as? String)?.let { schemaId ->
                // Select the package schema before applying its theme so the
                // keyboard switcher resolves the correct default layout.
                RimeDaemon.getFirstSessionOrNull()?.runOnReady { selectSchema(schemaId) }
            }
            val theme = ComponentThemeLoader.loadTheme(componentManifest)
            applyTheme(theme)
        } catch (t: Throwable) {
            when (t) {
                is CancellationException -> throw t
                is IllegalArgumentException,
                is IllegalStateException,
                is NoSuchElementException -> {
                    Timber.w(t, "Active IME package theme is unusable; falling back to Default.zip")
                    healStaleActiveManifest(packageId, staleRimeFiles)
                }
                else -> throw t
            }
        }
    }

    private suspend fun healStaleActiveManifest(
        stalePackageId: String? = null,
        staleRimeFiles: List<String> = emptyList(),
    ) {
        activeManifestFile.delete()
        stalePackageId?.let { File(imesDir, it).deleteRecursively() }
        staleRimeFiles.forEach { relative ->
            File(DataManager.userDataDir, relative).delete()
        }
        installBundledDefaultPackage()
        val defaultPackage = packageFile(DEFAULT_PACKAGE_FILE_NAME)
        if (!defaultPackage.isFile) {
            Timber.w("Bundled Default.zip is missing; no IME package can be activated")
            return
        }
        withContext(Dispatchers.IO) {
            activate(defaultPackage, applyThemeAfter = false)
        }
        // Re-enter restore now that the default package is active; this applies
        // the theme synchronously on the caller's thread.
        restoreActiveTheme()
    }

    /** Copy a package zip into the persistent app-managed IME package library. */
    fun importPackage(source: File): File {
        val meta = readPackageMeta(source)
        requireSafeFileName(meta.schemaId, "schema_id")
        val target = File(imesDir, "${meta.schemaId}.zip")
        source.copyTo(target, overwrite = true)
        return target
    }

    /** True when [fileName] is the app-shipped default package. */
    fun isDefaultPackage(fileName: String): Boolean = fileName == DEFAULT_PACKAGE_FILE_NAME

    /**
     * Delete a non-default, non-active IME package zip and its extracted state.
     * Returns false when the package is the default, is currently active, or no
     * longer exists.
     */
    fun deletePackage(fileName: String): Boolean {
        if (isDefaultPackage(fileName)) return false
        if (!isSafeFileName(fileName)) return false
        val packageFile = packageFile(fileName)
        if (!packageFile.isFile) return false
        if (isActivePackage(packageFile)) return false
        if (!isSafeFileName(packageFile.nameWithoutExtension)) return false
        packageFile.delete()
        File(imesDir, packageFile.nameWithoutExtension).deleteRecursively()
        return true
    }

    /** Activate a package zip, replacing the currently active package. */
    fun activate(
        packageFile: File,
        applyThemeAfter: Boolean = true,
    ) {
        if (!packageFile.isFile) {
            throw IllegalArgumentException("IME package not found: $packageFile")
        }
        if (isActivePackage(packageFile)) return

        synchronized(activationLock) {
            // Re-check after acquiring the lock: a concurrent activation may
            // have completed while this thread waited.
            if (isActivePackage(packageFile)) return
            if (!activating.compareAndSet(false, true)) {
                throw IllegalStateException("IME package activation is already in progress")
            }
            try {
                val prepared = preparePackage(packageFile)
                val backup =
                    try {
                        backupActive()
                    } catch (t: Throwable) {
                        prepared.stagingDir.deleteRecursively()
                        throw t
                    }
                try {
                    uninstallActive()
                    cleanLegacySchemas()
                    commitPreparedPackage(prepared)
                    if (applyThemeAfter) applyTheme(prepared.theme)
                } catch (t: Throwable) {
                    rollbackActive(backup, prepared)
                    throw t
                } finally {
                    prepared.stagingDir.deleteRecursively()
                    backup.root.deleteRecursively()
                }
            } finally {
                activating.set(false)
            }
        }
    }

    /** True while an IME package is being activated; input should be blocked. */
    fun isActivating(): Boolean = activating.get()

    private fun preparePackage(packageFile: File): PreparedPackage {
        requireSafeFileName(packageFile.nameWithoutExtension, "package file name")
        val stagingDir =
            File(imesDir, ".staging-${packageFile.nameWithoutExtension}-${System.nanoTime()}")
                .apply { mkdirs() }
        try {
            val meta = readPackageMeta(packageFile)
            requireSafeFileName(meta.schemaId, "schema_id")
            val stagedEntries = mutableListOf<StagedEntry>()
            val manifestNode: Node.Mapping
            ZipFile(packageFile).use { zip ->
                manifestNode = readManifestNode(zip)
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = entry.name
                    val normalizedName = File(name).toPath().normalize()
                    if (normalizedName.isAbsolute || normalizedName.startsWith("..")) {
                        throw IllegalArgumentException("Unsafe path in IME package: $name")
                    }
                    val stagingTarget = File(stagingDir, name)
                    val normalizedStaging = stagingTarget.toPath().toAbsolutePath().normalize()
                    if (!normalizedStaging.startsWith(stagingDir.toPath().toAbsolutePath().normalize())) {
                        throw IllegalArgumentException("Unsafe path in IME package: $name")
                    }
                    stagingTarget.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        stagingTarget.outputStream().use { output -> input.copyTo(output) }
                    }
                    val isRimeFile = name.startsWith("rime/") || name.endsWith(".schema.yaml")
                    val relative =
                        if (isRimeFile && name.startsWith("rime/")) name.removePrefix("rime/") else name
                    stagedEntries += StagedEntry(name, relative, isRimeFile)
                }
            }
            val rimeFiles = stagedEntries.filter { it.isRime }.map { it.relative }.distinct()
            if (rimeFiles.none { it == "${meta.schemaId}.schema.yaml" }) {
                throw IllegalArgumentException("IME package does not contain ${meta.schemaId}.schema.yaml")
            }
            val theme = loadPackageTheme(stagingDir, manifestNode, meta.name)
            return PreparedPackage(
                meta = meta,
                packageId = packageFile.nameWithoutExtension,
                rimeFiles = rimeFiles,
                theme = theme,
                stagingDir = stagingDir,
                stagedEntries = stagedEntries,
            )
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw t
        }
    }

    private fun commitPreparedPackage(prepared: PreparedPackage) {
        val stateDir = File(imesDir, prepared.packageId).apply { mkdirs() }
        prepared.stagedEntries.forEach { entry ->
            val target =
                if (entry.isRime) {
                    File(DataManager.userDataDir, entry.relative)
                } else {
                    File(stateDir, entry.zipName)
                }
            val normalized = target.toPath().toAbsolutePath().normalize()
            val allowedRoot =
                if (entry.isRime) {
                    DataManager.userDataDir.toPath().toAbsolutePath().normalize()
                } else {
                    stateDir.toPath().toAbsolutePath().normalize()
                }
            if (!normalized.startsWith(allowedRoot)) {
                throw IllegalArgumentException("Unsafe path in IME package: ${entry.zipName}")
            }
            target.parentFile?.mkdirs()
            File(prepared.stagingDir, entry.zipName).copyTo(target, overwrite = true)
        }

        deploySchemas(prepared.rimeFiles, prepared.meta.schemaId)
        val schemaIds =
            prepared.rimeFiles
                .filter { it.endsWith(".schema.yaml") }
                .map { it.substringAfterLast('/').removeSuffix(".schema.yaml") }
                .distinct()
                .toMutableList()
                .also { ids ->
                    ids.remove(prepared.meta.schemaId)
                    ids.add(0, prepared.meta.schemaId)
                }
        SchemaListUpdater.setSchemas(customFile(), schemaIds)
        // Reload Rime config so the new schema list is visible to the
        // Schemata settings screen immediately after activation.
        RimeDaemon.getFirstSessionOrNull()?.run { updateConfig() }
        RimeDaemon.getFirstSessionOrNull()?.run { selectSchema(prepared.meta.schemaId) }
        writeActiveManifest(prepared.meta, prepared.rimeFiles)
    }

    private fun backupActive(): ActiveBackup {
        val root =
            File(imesDir, ".backup-${System.nanoTime()}").apply { mkdirs() }
        val active = activeManifestFile
        val data = if (active.isFile) readActiveManifest(active) else emptyMap()
        if (active.isFile) {
            active.copyTo(File(root, "active-manifest.yaml"), overwrite = true)
        }

        (data["package_id"] as? String)?.let { packageId ->
            val stateDir = File(imesDir, packageId)
            if (stateDir.isDirectory) {
                stateDir.copyRecursively(File(root, "state"), overwrite = true)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val rimeFiles = (data["rime_files"] as? List<String>) ?: emptyList()
        rimeFiles.forEach { relative ->
            val file = File(DataManager.userDataDir, relative)
            if (file.isFile) {
                val dest = File(root, "rime/$relative")
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
            }
        }

        // cleanLegacySchemas() removes schema files and the legacy
        // schema-packages dir; preserve them so a failed activation can roll back.
        DataManager.userDataDir.listFiles { file ->
            file.isFile && file.name.endsWith(".schema.yaml")
        }?.forEach { file ->
            file.copyTo(File(root, "legacy-schemas/${file.name}"), overwrite = true)
        }
        val legacySchemaPackages = File(DataManager.userDataDir, "schema-packages")
        if (legacySchemaPackages.isDirectory) {
            legacySchemaPackages.copyRecursively(File(root, "legacy-schema-packages"), overwrite = true)
        }

        val custom = customFile()
        if (custom.isFile) {
            custom.copyTo(File(root, "default.custom.yaml"), overwrite = true)
        }

        if (buildDir.isDirectory) {
            buildDir.copyRecursively(File(root, "build"), overwrite = true)
        }
        return ActiveBackup(root)
    }

    private fun rollbackActive(
        backup: ActiveBackup,
        prepared: PreparedPackage,
    ) {
        activeManifestFile.delete()
        File(imesDir, prepared.packageId).deleteRecursively()
        prepared.rimeFiles.forEach { relative ->
            File(DataManager.userDataDir, relative).delete()
        }

        buildDir.deleteRecursively()
        File(backup.root, "build").takeIf { it.isDirectory }?.copyRecursively(buildDir, overwrite = true)

        val rimeBackup = File(backup.root, "rime")
        if (rimeBackup.isDirectory) {
            rimeBackup.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relative = file.relativeTo(rimeBackup).path
                    val target = File(DataManager.userDataDir, relative)
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                }
            }
        }

        val legacySchemas = File(backup.root, "legacy-schemas")
        if (legacySchemas.isDirectory) {
            legacySchemas.listFiles()?.forEach { file ->
                file.copyTo(File(DataManager.userDataDir, file.name), overwrite = true)
            }
        }
        val legacySchemaPackages = File(backup.root, "legacy-schema-packages")
        if (legacySchemaPackages.isDirectory) {
            legacySchemaPackages.copyRecursively(
                File(DataManager.userDataDir, "schema-packages"),
                overwrite = true,
            )
        }

        val stateBackup = File(backup.root, "state")
        if (stateBackup.isDirectory) {
            val packageId =
                readActiveManifest(File(backup.root, "active-manifest.yaml"))["package_id"] as? String
            if (packageId != null) {
                val stateDir = File(imesDir, packageId).apply { mkdirs() }
                stateBackup.copyRecursively(stateDir, overwrite = true)
            }
        }

        File(backup.root, "active-manifest.yaml").takeIf { it.isFile }?.copyTo(activeManifestFile, overwrite = true)
        File(backup.root, "default.custom.yaml").takeIf { it.isFile }?.copyTo(customFile(), overwrite = true)

        // Keep the running Rime engine in sync with the restored files: the
        // failed activation may already have reloaded the new schema list and
        // selected the new package's schema.
        val restoredManifest = File(backup.root, "active-manifest.yaml")
        if (restoredManifest.isFile) {
            val oldSchemaId = readActiveManifest(restoredManifest)["schema_id"] as? String
            if (oldSchemaId != null) {
                RimeDaemon.getFirstSessionOrNull()?.run {
                    updateConfig()
                    selectSchema(oldSchemaId)
                }
            }
        }
    }

    private fun requireSafeFileName(
        value: String,
        what: String,
    ) {
        if (!isSafeFileName(value)) {
            throw IllegalArgumentException("$what contains unsafe characters: '$value'")
        }
    }

    private fun isSafeFileName(value: String): Boolean =
        value.isNotEmpty() && value != "." && value != ".." && value.matches(SAFE_FILE_NAME)

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]+")

    /** Uninstall the active package, preserving user/generated data. */
    fun uninstallActive() {
        val active = activeManifestFile
        if (!active.isFile) return
        val data = readActiveManifest(active)

        @Suppress("UNCHECKED_CAST")
        val rimeFiles = (data["rime_files"] as? List<String>) ?: emptyList()
        rimeFiles.forEach { relative ->
            val file = File(DataManager.userDataDir, relative)
            if (file.isFile) {
                file.delete()
            }
        }

        (data["package_id"] as? String)?.let { packageId ->
            val stateDir = File(imesDir, packageId)
            if (stateDir.isDirectory) {
                stateDir.deleteRecursively()
            }
        }

        // Compiled artifacts are derived from the package and must not survive a switch.
        buildDir.listFiles()?.forEach { it.deleteRecursively() }

        active.delete()
    }

    /**
     * Remove leftover activation staging/backup dirs from a previous process
     * death. Runs under [activationLock] so it can never delete a live
     * activation's staging/backup dirs. If no active manifest exists, every
     * state dir under IMEs/ is stale and is removed too; this is an explicit
     * fallback-to-Default policy rather than an attempt to recover the previous
     * custom package's backup.
     */
    private fun cleanupInterruptedActivation() {
        synchronized(activationLock) {
            val activeExists = activeManifestFile.isFile
            imesDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
                val name = dir.name
                val isTemp = name.startsWith(".staging-") || name.startsWith(".backup-")
                if (isTemp || (!activeExists && !name.startsWith("."))) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    /**
     * Remove schema files and legacy schema-layout package state that may have
     * been left behind by previous packages or manual schema-list edits. A
     * self-contained IME package owns the whole Rime schema set, so switching
     * packages must start from a clean schema section.
     */
    private fun cleanLegacySchemas() {
        DataManager.userDataDir.listFiles { file ->
            file.isFile && file.name.endsWith(".schema.yaml")
        }?.forEach { file ->
            file.delete()
        }
        File(DataManager.userDataDir, "schema-packages").deleteRecursively()
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
                size = packageFile.length(),
                sha256 = sha256(packageFile),
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

    private fun loadPackageTheme(
        stateDir: File,
        manifestNode: Node.Mapping,
        fallbackName: String,
    ): Theme {
        val componentManifest =
            listOf(File(stateDir, "component.yaml"), File(stateDir, "manifest.yaml"))
                .firstOrNull { it.isFile && ComponentThemeLoader.isComponentManifest(it) }
        if (componentManifest != null) {
            return ComponentThemeLoader.loadTheme(componentManifest)
        }
        // Legacy/simple fallback: a standalone theme.yaml without components.
        val themeFile = File(stateDir, "theme.yaml")
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
            val name = node["name"]?.string ?: fallbackName
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

    private fun deploySchemas(
        rimeFiles: List<String>,
        schemaId: String,
    ) {
        val mainSchema = "$schemaId.schema.yaml"
        val schemaFiles =
            rimeFiles
                .filter { it.endsWith(".schema.yaml") }
                .sortedBy { if (it == mainSchema) 1 else 0 }
        if (schemaFiles.none { it == mainSchema }) {
            throw IllegalArgumentException("IME package does not contain main schema $mainSchema")
        }
        RimeDaemon.notifyDeployStart()
        try {
            schemaFiles.forEach { name ->
                val file = File(DataManager.userDataDir, name)
                if (!file.isFile) {
                    throw IllegalArgumentException("Schema file missing after extraction: $name")
                }
                if (!Rime.deployRimeSchemaFile(file.absolutePath)) {
                    throw IllegalStateException("Rime failed to deploy schema: $name")
                }
            }
            RimeDaemon.notifyDeploySuccess()
        } catch (t: Throwable) {
            RimeDaemon.notifyDeployFailure()
            throw t
        }
    }

    private fun writeActiveManifest(
        meta: ImePackageMeta,
        rimeFiles: List<String>,
    ) {
        val data =
            linkedMapOf<String, Any?>(
                "active_package" to meta.fileName,
                "package_id" to meta.packageId,
                "name" to meta.name,
                "version" to meta.version,
                "schema_id" to meta.schemaId,
                "default_keyboard" to meta.defaultKeyboard,
                "package_size" to meta.size,
                "package_sha256" to meta.sha256,
                "rime_files" to rimeFiles,
            )
        activeManifestFile.parentFile?.mkdirs()
        activeManifestFile.writeText(SnakeYaml().dump(data), Charsets.UTF_8)
    }

    private fun readActiveManifest(file: File): Map<String, Any?> =
        try {
            val loaded = SnakeYaml().load<Any?>(file.readText(Charsets.UTF_8))
            loaded as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse active manifest ${file.name}; treating it as empty")
            emptyMap()
        }

    private fun customFile(): File = File(DataManager.userDataDir, "default.custom.yaml")

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

    /**
     * Apply a package theme on the main thread. Package activation runs on a
     * background dispatcher, while ThemeManager listeners rebuild input views,
     * which must happen on the thread that created the view hierarchy.
     */
    private fun applyTheme(theme: Theme) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ThemeManager.applySchemaLayout(theme, replaceTheme = true)
        } else {
            Handler(Looper.getMainLooper()).post {
                ThemeManager.applySchemaLayout(theme, replaceTheme = true)
            }
        }
    }

    private data class ImePackageMeta(
        val fileName: String,
        val packageId: String,
        val name: String,
        val version: String?,
        val schemaId: String,
        val defaultKeyboard: String?,
        val size: Long,
        val sha256: String,
    )

    private data class StagedEntry(
        val zipName: String,
        val relative: String,
        val isRime: Boolean,
    )

    private data class PreparedPackage(
        val meta: ImePackageMeta,
        val packageId: String,
        val rimeFiles: List<String>,
        val theme: Theme,
        val stagingDir: File,
        val stagedEntries: List<StagedEntry>,
    )

    private data class ActiveBackup(val root: File)
}
