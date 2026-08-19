// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.base

import android.content.res.AssetManager
import android.os.Build
import android.os.Environment
import androidx.preference.PreferenceManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.schema.PackageStore
import com.osfans.trime.util.FileUtils
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.Yaml
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DataManager {
    private const val DEFAULT_CUSTOM_FILE_NAME = "default.custom.yaml"

    private const val DATA_CHECKSUMS_NAME = "checksums.json"

    private const val SCHEMA_LIST_CUSTOM_PATCH = """
      patch:
        schema_list:
          - schema: luna_pinyin
          - schema: luna_pinyin_simp
    """

    private val lock = ReentrantLock()

    private val json by lazy { Json }

    private fun deserializeDataChecksums(raw: String): DataChecksums = json.decodeFromString<DataChecksums>(raw)

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    private val dataDir: File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Timber.d("Using device protected storage")
            appContext.createDeviceProtectedStorageContext().dataDir
        } else {
            File(appContext.applicationInfo.dataDir)
        }

    private fun AssetManager.dataChecksums(): DataChecksums = open(DATA_CHECKSUMS_NAME)
        .bufferedReader()
        .use { it.readText() }
        .let { deserializeDataChecksums(it) }

    /** App-managed Rime user data directory. No broad storage permission needed. */
    val defaultDataDir = File(appContext.getExternalFilesDir(null), "rime")

    /** Legacy public-storage Rime directory, kept for one-time migration only. */
    private val legacyDefaultDataDir = File(Environment.getExternalStorageDirectory(), "rime")

    val sharedDataDir = File(appContext.getExternalFilesDir(null), "shared").also { it.mkdirs() }

    /**
     * Rime's current user data directory. In the package model this is the
     * active package's workspace (or the Default workspace before any package
     * has been activated), never a shared `/rime` dir that packages are copied
     * into.
     */
    val userDataDir
        get() =
            (PackageStore.activeWorkspaceDir() ?: PackageStore.defaultWorkspaceDir())
                .also { it.mkdirs() }

    val prebuiltDataDir = File(sharedDataDir, "build")
    val stagingDir get() = File(userDataDir, "build")

    /**
     * One-time migration from the legacy public `/rime` directory (or a custom
     * `profile_user_data_dir` path) into the app-managed directory.
     *
     * Must be called after `AppPrefs.initDefault` and before anything touches
     * [userDataDir]. A marker file prevents repeated copies if the preference
     * was not cleared for some reason.
     */
    fun migrateLegacyUserDataIfNeeded() {
        migrateLegacyPublicRimeIfNeeded()
        migrateManagedRimeToPackageWorkspaceIfNeeded()
    }

    private fun migrateLegacyPublicRimeIfNeeded() {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val managed = defaultDataDir
        val configuredPath = sharedPrefs.getString(AppPrefs.Profile.USER_DATA_DIR, null)
        val legacy = configuredPath?.let(::File) ?: legacyDefaultDataDir
        if (legacy == managed) {
            sharedPrefs.edit().remove(AppPrefs.Profile.USER_DATA_DIR).apply()
            return
        }
        if (!legacy.isDirectory) {
            if (configuredPath != null) {
                sharedPrefs.edit().remove(AppPrefs.Profile.USER_DATA_DIR).apply()
            }
            return
        }
        val marker = File(dataDir, MIGRATION_MARKER)
        if (marker.isFile) {
            sharedPrefs.edit().remove(AppPrefs.Profile.USER_DATA_DIR).apply()
            return
        }
        // If the managed dir is already an active IME package install, do not
        // overwrite it with legacy data; the app is already running from here.
        if (File(managed, "IMEs/active-manifest.yaml").isFile) {
            sharedPrefs.edit().remove(AppPrefs.Profile.USER_DATA_DIR).apply()
            return
        }
        try {
            managed.mkdirs()
            legacy.copyRecursively(managed, overwrite = true)
            marker.writeText(legacy.absolutePath)
            sharedPrefs.edit().remove(AppPrefs.Profile.USER_DATA_DIR).apply()
            Timber.i("Migrated Rime user data from $legacy to $managed")
        } catch (e: Exception) {
            Timber.w(e, "Failed to migrate Rime user data from $legacy to $managed")
        }
    }

    /**
     * One-time migration from the old shared `/rime` directory (the previous
     * "all packages in one user_data_dir" layout) into per-package workspaces.
     *
     * If the old active manifest identifies a package, that package becomes a
     * workspace and is activated. Otherwise the data is preserved in a special
     * `Migrated` workspace (the old data has no corresponding package) and
     * Default is activated.
     */
    private fun migrateManagedRimeToPackageWorkspaceIfNeeded() {
        if (PackageStore.activePackageId() != null) return
        val managed = defaultDataDir
        if (!managed.isDirectory) return
        val marker = File(dataDir, PACKAGE_MIGRATION_MARKER)
        if (marker.isFile) {
            if (PackageStore.activePackageId() == null) {
                PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
            }
            return
        }
        val activeManifest = File(managed, "IMEs/active-manifest.yaml")
        val activePackageId =
            readActivePackageId(activeManifest)
                ?.takeIf { PackageStore.isSafePackageId(it) }
        val targetWorkspace =
            if (activePackageId != null && PackageStore.isSafePackageId(activePackageId)) {
                PackageStore.workspaceDir(activePackageId)
            } else {
                PackageStore.workspaceDir(PackageStore.MIGRATED_PACKAGE_ID)
            }
        if (targetWorkspace.listFiles()?.isNotEmpty() == true) {
            if (activePackageId != null) {
                writeMigratedCompiledMarker(targetWorkspace, activePackageId)
                if (activePackageId == PackageStore.DEFAULT_PACKAGE_ID ||
                    PackageStore.isCompiled(activePackageId)
                ) {
                    PackageStore.setActivePackage(activePackageId)
                } else {
                    // Do not start Rime with a migrated package that cannot be
                    // opened; let ensureDefaultPackageReady compile/activate it
                    // later from the package list.
                    PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                }
            } else {
                PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
            }
            marker.writeText(managed.absolutePath)
            return
        }
        try {
            targetWorkspace.deleteRecursively()
            targetWorkspace.mkdirs()
            managed.listFiles()?.forEach { child ->
                if (child.name == "IMEs") return@forEach
                val target = File(targetWorkspace, child.name)
                if (child.isDirectory) {
                    child.copyRecursively(target, overwrite = true)
                } else {
                    child.copyTo(target, overwrite = true)
                }
            }
            if (activePackageId != null) {
                val stateDir = File(managed, "IMEs/$activePackageId")
                if (stateDir.isDirectory) {
                    stateDir.listFiles()?.forEach { child ->
                        val target = File(targetWorkspace, child.name)
                        if (child.isDirectory) {
                            child.copyRecursively(target, overwrite = true)
                        } else {
                            child.copyTo(target, overwrite = true)
                        }
                    }
                }
            }
            // Preserve non-active old IME package zips so they can be compiled
            // and switched to later.
            File(managed, "IMEs").listFiles { file ->
                file.isFile && file.extension.equals("zip", ignoreCase = true)
            }?.forEach { oldZip ->
                val id = oldZip.nameWithoutExtension
                if (PackageStore.isSafePackageId(id) && id != activePackageId) {
                    val dest = File(PackageStore.packageDir(id), "package.zip")
                    dest.parentFile?.mkdirs()
                    oldZip.copyTo(dest, overwrite = true)
                }
            }
            if (activePackageId != null) {
                writeMigratedCompiledMarker(targetWorkspace, activePackageId)
            }
            marker.writeText(managed.absolutePath)
            if (activePackageId != null &&
                (activePackageId == PackageStore.DEFAULT_PACKAGE_ID ||
                    PackageStore.isCompiled(activePackageId))
            ) {
                PackageStore.setActivePackage(activePackageId)
                Timber.i("Migrated managed /rime to package workspace $activePackageId")
            } else if (activePackageId != null) {
                PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                Timber.w(
                    "Migrated package $activePackageId is not compiled/usable; activating Default",
                )
            } else {
                PackageStore.setActivePackage(PackageStore.DEFAULT_PACKAGE_ID)
                Timber.i("No matching package for managed /rime; preserved as Migrated workspace")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to migrate managed /rime to package workspace")
        }
    }

    private fun readActivePackageId(manifest: File): String? {
        if (!manifest.isFile) return null
        return runCatching {
            val loaded = Yaml().load<Any?>(manifest.readText(Charsets.UTF_8))
            (loaded as? Map<*, *>)?.get("package_id") as? String
        }.getOrNull()
    }

    private fun writeMigratedCompiledMarker(
        workspace: File,
        packageId: String,
    ) {
        // Only mark migrated data as compiled when there is actual evidence it
        // was deployed before and its theme is usable; otherwise let the normal
        // compile/fallback flow take over instead of creating a package that
        // looks compiled but cannot be opened.
        val compiledEvidence =
            File(workspace, "build").isDirectory ||
                File(workspace, "default.custom.yaml").isFile
        if (!compiledEvidence || !ImePackageManager.hasUsableTheme(workspace)) return
        val content =
            if (packageId == PackageStore.DEFAULT_PACKAGE_ID) {
                val source = File(sharedDataDir, "Default.zip")
                if (source.isFile) sha256(source) else "ok"
            } else {
                "ok"
            }
        File(workspace, "compiled.marker").writeText(content)
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

    private const val MIGRATION_MARKER = ".trime-migrated-to-managed"
    private const val PACKAGE_MIGRATION_MARKER = ".trime-migrated-to-package-workspaces"

    /**
     * Return the absolute path of the compiled config file
     * based on given resource id.
     *
     * @param resourceId usually equals the config file name without the extension
     * @return the absolute path of the compiled config file
     */
    @JvmStatic
    fun resolveDeployedResourcePath(resourceId: String): String {
        val defaultPath = File(stagingDir, "$resourceId.yaml")
        if (!defaultPath.exists()) {
            val fallbackPath = File(prebuiltDataDir, "$resourceId.yaml")
            if (fallbackPath.exists()) return fallbackPath.absolutePath
        }
        return defaultPath.absolutePath
    }

    fun sync() = lock.withLock {
        val oldChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            oldChecksumsFile
                .runCatching { deserializeDataChecksums(bufferedReader().use { it.readText() }) }
                .getOrElse { DataChecksums("", emptyMap()) }

        val newChecksums = appContext.assets.dataChecksums()

        DataDiff.diff(oldChecksums, newChecksums).sortedByDescending { it.ordinal }.forEach {
            Timber.d("Diff: $it")
            when (it) {
                is DataDiff.CreateFile,
                is DataDiff.UpdateFile,
                -> {
                    val destPath = sharedDataDir.resolveSibling(it.path).absolutePath
                    ResourceUtils.copyFile(it.path, destPath)
                }
                is DataDiff.DeleteDir,
                is DataDiff.DeleteFile,
                -> FileUtils.delete(sharedDataDir.resolve(it.path.substringAfterLast('/'))).getOrThrow()
            }
        }

        ResourceUtils.copyFile(DATA_CHECKSUMS_NAME, dataDir.resolve(DATA_CHECKSUMS_NAME).absolutePath)

        ensureDefaultCustomFile()

        Timber.d("Synced!")
    }

    /** Create the minimal default.custom.yaml in the current user data dir. */
    fun ensureDefaultCustomFile() {
        val custom = userDataDir.resolve(DEFAULT_CUSTOM_FILE_NAME)
        if (!custom.exists()) {
            if (custom.createNewFile()) {
                custom.writeText(SCHEMA_LIST_CUSTOM_PATCH.trimIndent())
            }
        }
    }
}
