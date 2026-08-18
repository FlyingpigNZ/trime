// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.base

import android.content.res.AssetManager
import android.os.Build
import android.os.Environment
import androidx.preference.PreferenceManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.FileUtils
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
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

    val userDataDir
        get() = defaultDataDir.also { it.mkdirs() }

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

    private const val MIGRATION_MARKER = ".trime-migrated-to-managed"

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

        val custom = userDataDir.resolve(DEFAULT_CUSTOM_FILE_NAME)
        if (!custom.exists()) {
            if (custom.createNewFile()) {
                custom.writeText(SCHEMA_LIST_CUSTOM_PATCH.trimIndent())
            }
        }

        Timber.d("Synced!")
    }
}
