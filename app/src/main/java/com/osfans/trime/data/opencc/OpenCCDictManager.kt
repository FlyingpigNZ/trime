// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.opencc

import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.opencc.dict.Dictionary
import com.osfans.trime.data.opencc.dict.OpenCCDictionary
import com.osfans.trime.data.opencc.dict.TextDictionary
import timber.log.Timber
import java.io.File
import kotlin.system.measureTimeMillis

object OpenCCDictManager {
    init {
        System.loadLibrary("rime_jni")
    }

    private val sharedDir = File(DataManager.sharedDataDir, "opencc").also { it.mkdirs() }
    private val userDir get() = File(DataManager.userDataDir, "opencc").also { it.mkdirs() }

    fun sharedDictionaries(): List<Dictionary> = sharedDir
        .listFiles()
        ?.mapNotNull { Dictionary.new(it) } ?: listOf()

    fun userDictionaries(): List<Dictionary> = userDir
        .listFiles()
        ?.mapNotNull { Dictionary.new(it) } ?: listOf()

    fun getAllDictionaries(): List<Dictionary> = sharedDictionaries() + userDictionaries()

    /**
     * Convert internal text dict to opencc format
     */
    @JvmStatic
    fun buildOpenCCDict() {
        for (d in getAllDictionaries()) {
            if (d is TextDictionary) {
                val result: Result<OpenCCDictionary>
                measureTimeMillis {
                    result = runCatching { d.toOpenCCDictionary() }
                }.also {
                    result
                        .onSuccess { r ->
                            Timber.d("Took $it to convert to $r")
                        }.onFailure {
                            Timber.e(it, "Failed to convert $d")
                        }
                }
            }
        }
    }

    @JvmStatic
    external fun openCCDictConv(
        src: String,
        dest: String,
        mode: Boolean,
    )

    const val MODE_BIN_TO_TXT = true // OCD(2) to TXT
    const val MODE_TXT_TO_BIN = false // TXT to OCD2
}
