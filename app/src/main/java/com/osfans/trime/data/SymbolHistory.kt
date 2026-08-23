// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data

import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class SymbolHistory(
    val capacity: Int,
) : LinkedHashMap<String, String>(0, .75f, true) {
    companion object {
        const val FILE_NAME = "symbol_history"
    }

    /** Resolved lazily; no file I/O happens at construction. */
    private val file: File
        get() = appContext.filesDir.resolve(FILE_NAME)

    /** Load the persisted history from disk (off the main thread). */
    suspend fun load() = withContext(Dispatchers.IO) {
        runCatching { file.readLines() }.getOrElse { t ->
            Timber.w(t, "Failed to read symbol history")
            emptyList()
        }.forEach {
            if (it.isNotBlank()) {
                put(it, it)
            }
        }
    }

    /** Persist the current history atomically (off the main thread). */
    suspend fun save() = withContext(Dispatchers.IO) {
        val content = values.joinToString("\n")
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                // renameTo can fail across some filesystems; fall back to a
                // direct write so the history is still persisted.
                file.writeText(content)
                tmp.delete()
            }
        }.onFailure { t -> Timber.w(t, "Failed to write symbol history") }
    }

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity

    fun insert(s: String) = put(s, s)

    fun toOrderedList() = values.toList().reversed()
}
