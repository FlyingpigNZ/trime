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
import org.yaml.snakeyaml.Yaml as SnakeYaml
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * IME package manager for the self-contained package model.
 *
 * Packages live in `/rime/IMEs/` as zip files. Activating a package extracts
 * its `rime/...` files into the Rime user data dir, extracts its definition files
 * into a persistent per-package state dir under `/rime/IMEs/`, deploys the
 * Rime schemas, and writes an active manifest that doubles as the uninstall
 * manifest.
 */
object ImePackageManager {
    @Volatile
    private var activating = false

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
    )

    fun listPackages(): List<ImePackage> {
        imesDir.mkdirs()
        return imesDir.listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file ->
                runCatching { readPackageMeta(file) }.getOrElse { e ->
                    Timber.w("Failed to read IME package ${file.name}: ${e.message}")
                    null
                }?.let { meta -> ImePackage(meta.fileName, meta.name, meta.version) }
            }
            ?: emptyList()
    }

    fun activePackageFileName(): String? =
        activeManifestFile
            .takeIf { it.isFile }
            ?.let { readActiveManifest(it)["active_package"] as? String }

    fun packageFile(fileName: String): File = File(imesDir, fileName)

    /** Registry view of the active IME package's explicit schema→keyboard binding. */
    fun registry(): SchemaLayoutRegistry {
        val active = activeManifestFile
        if (!active.isFile) return SchemaLayoutRegistry.Empty
        val data = readActiveManifest(active)
        val schemaId = data["schema_id"] as? String ?: return SchemaLayoutRegistry.Empty
        val defaultKeyboard = data["default_keyboard"] as? String ?: return SchemaLayoutRegistry.Empty
        return SchemaLayoutRegistry.fromDefaultKeyboards(mapOf(schemaId to defaultKeyboard))
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
     * and compiled Rime data already exist under `/rime`; this only reloads the
     * package's component manifest and applies it as the active theme.
     */
    suspend fun restoreActiveTheme() {
        val active = activeManifestFile
        if (!active.isFile) return
        val data = readActiveManifest(active)
        val packageId = data["package_id"] as? String ?: return
        (data["schema_id"] as? String)?.let { schemaId ->
            // Select the package schema before applying its theme so the
            // keyboard switcher resolves the correct default layout.
            RimeDaemon.getFirstSessionOrNull()?.runOnReady { selectSchema(schemaId) }
        }
        val stateDir = File(imesDir, packageId)
        val componentManifest =
            listOf(File(stateDir, "component.yaml"), File(stateDir, "manifest.yaml"))
                .firstOrNull { it.isFile && ComponentThemeLoader.isComponentManifest(it) }
                ?: return
        val theme = ComponentThemeLoader.loadTheme(componentManifest, standard = null, fallbackRoot = stateDir)
        applyTheme(theme)
    }

    /** Copy a package zip into the persistent `/rime/IMEs/` library. */
    fun importPackage(source: File): File {
        val meta = readPackageMeta(source)
        val target = File(imesDir, "${meta.schemaId}.zip")
        source.copyTo(target, overwrite = true)
        return target
    }

    /** Activate a package zip, replacing the currently active package. */
    fun activate(packageFile: File) {
        if (!packageFile.isFile) {
            throw IllegalArgumentException("IME package not found: $packageFile")
        }
        if (isActivePackage(packageFile)) return
        activating = true
        try {
            uninstallActive()
            val meta = readPackageMeta(packageFile)
            val packageId = packageFile.nameWithoutExtension
            val stateDir = File(imesDir, packageId).apply { mkdirs() }
            val rimeFiles = mutableListOf<String>()

            ZipFile(packageFile).use { zip ->
                val manifestNode = readManifestNode(zip)
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = entry.name
                    val isRimeFile = name.startsWith("rime/") || name.endsWith(".schema.yaml")
                    val target =
                        if (isRimeFile) {
                            val relative = if (name.startsWith("rime/")) name.removePrefix("rime/") else name
                            rimeFiles += relative
                            File(DataManager.userDataDir, relative)
                        } else {
                            File(stateDir, name)
                        }
                    val normalized = target.toPath().toAbsolutePath().normalize()
                    val allowedRoot =
                        if (isRimeFile) {
                            DataManager.userDataDir.toPath().toAbsolutePath().normalize()
                        } else {
                            stateDir.toPath().toAbsolutePath().normalize()
                        }
                    if (!normalized.startsWith(allowedRoot)) {
                        throw IllegalArgumentException("Unsafe path in IME package: $name")
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }

                deploySchemas(rimeFiles, meta.schemaId)
                val schemaIds =
                    rimeFiles
                        .filter { it.endsWith(".schema.yaml") }
                        .map { it.substringAfterLast('/').removeSuffix(".schema.yaml") }
                        .distinct()
                        .toMutableList()
                        .also { ids ->
                            ids.remove(meta.schemaId)
                            ids.add(0, meta.schemaId)
                        }
                SchemaListUpdater.setSchemas(customFile(), schemaIds)
                RimeDaemon.getFirstSessionOrNull()?.run { selectSchema(meta.schemaId) }
                val theme = loadPackageTheme(stateDir, manifestNode, meta.name)
                writeActiveManifest(meta, rimeFiles)
                applyTheme(theme)
            }
        } finally {
            activating = false
        }
    }

    /** True while an IME package is being activated; input should be blocked. */
    fun isActivating(): Boolean = activating

    /** Uninstall the active package, preserving user/generated data. */
    fun uninstallActive() {
        // A self-contained IME package replaces the whole input definition, so
        // any in-memory schema-layout bindings from a previously active package
        // must not leak into the new one.
        SchemaLayoutPackageManager.clearInstalled()
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
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
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
            return ComponentThemeLoader.loadTheme(
                manifestFile = componentManifest,
                standard = null,
                fallbackRoot = stateDir,
            )
        }
        // Legacy/simple fallback: a standalone theme.yaml without components.
        val themeFile = File(stateDir, "theme.yaml")
        if (themeFile.isFile) {
            val node = Yaml.Default.parseToYamlNode(themeFile.readText()).mapping
                ?: throw IllegalArgumentException("theme.yaml is not a mapping")
            val name = node["name"]?.string ?: fallbackName
            return Theme.decode(
                Node.Mapping(
                    LinkedHashMap(node.pairs).apply {
                        put(Node.Scalar("name"), Node.Scalar(name))
                        put(Node.Scalar("style"), node["style"] ?: Node.Mapping())
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
        activeManifestFile.writeText(SnakeYaml().dump(data))
    }

    private fun readActiveManifest(file: File): Map<String, Any?> {
        val loaded = SnakeYaml().load<Any?>(file.readText())
        return loaded as? Map<String, Any?> ?: emptyMap()
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
        Handler(Looper.getMainLooper()).post {
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
        val size: Long,
        val sha256: String,
    )
}
