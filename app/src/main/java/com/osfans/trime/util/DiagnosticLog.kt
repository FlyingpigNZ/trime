// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent, append-only diagnostic log.
 *
 * Rationale: the in-app log view tails `logcat` for the *current* pid only, and
 * librime's own INFO logs go to stderr rather than logcat, so a process death
 * (IME service restart, uncaught exception) leaves no retrievable trace.
 *
 * This log lives under the app's external files dir, deliberately **outside**
 * Rime's `user_data_dir` and `shared_data_dir`, so writing it can never change
 * the mtimes that librime's `detect_modifications` compares. Every line is
 * flushed immediately so the record survives [Process.killProcess] /
 * `exitProcess`.
 *
 * Only lifecycle / package / deploy events go through here — never hot paths.
 */
object DiagnosticLog {
    private const val DIR_NAME = "diagnostics"
    private const val FILE_NAME = "trime-diagnostics.log"
    private const val ROTATED_FILE_NAME = "$FILE_NAME.1"
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
    private const val MAX_FILE_BYTES = 256L * 1024L
    private const val MAX_FRAGMENT_CHARS = 64 * 1024
    private const val MAX_TAIL_LINES = 400
    private const val MAX_TAIL_BYTES = 128L * 1024L
    private const val MAX_EXPORT_LINES = 5000
    private const val MAX_EXPORT_BYTES = 512L * 1024L

    internal const val PROCESS_START_MARK = "=== process start"
    internal const val PROCESS_EXIT_MARK = "=== process exit"
    internal const val CRASH_MARK = "!!! crash"

    /** How the previous run of the same process ended. */
    internal enum class PreviousRun { NONE, CLEAN_EXIT, UNKNOWN_KILLED, CRASH }

    private val lock = Any()
    private val timestampFormatter =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
        }
    private val shutdownHookInstalled = AtomicBoolean(false)

    @Volatile
    private var logDir: File? = null

    /** Resolve the log directory. Safe to call more than once. */
    fun init(context: Context) {
        if (logDir != null) return
        synchronized(lock) {
            if (logDir != null) return
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            logDir = File(base, DIR_NAME).apply { mkdirs() }
        }
        installShutdownHook()
    }

    fun filePath(): String = runCatching { File(requireDir(), FILE_NAME).absolutePath }.getOrDefault("")

    fun i(
        tag: String,
        message: String,
    ) = append('I', tag, message)

    fun w(
        tag: String,
        message: String,
    ) = append('W', tag, message)

    fun e(
        tag: String,
        message: String,
        t: Throwable? = null,
    ) {
        append('E', tag, if (t == null) message else "$message | ${t.javaClass.name}: ${t.message}")
        if (t != null) writeRaw(t.stackTraceToString().take(MAX_FRAGMENT_CHARS))
    }

    /**
     * Record a process start together with how the previous run of this same
     * process ended — a crash, a clean VM exit, or neither (killed by the
     * system), which is the fact the in-app log cannot show.
     */
    fun processStarted(processName: String) {
        val previous = runCatching { classifyPreviousRun(readTailLines(MAX_TAIL_LINES, MAX_TAIL_BYTES), processName) }
            .getOrDefault(PreviousRun.NONE)
        append(
            'I',
            "process",
            "$PROCESS_START_MARK pid=${Process.myPid()} process=$processName " +
                "uptimeMs=${SystemClock.elapsedRealtime()} previous=$previous",
        )
    }

    /** Record a fatal crash and its stack trace before the process dies. */
    fun crash(t: Throwable) {
        append('F', "crash", "$CRASH_MARK ${t.javaClass.name}: ${t.message}")
        writeRaw(t.stackTraceToString().take(MAX_FRAGMENT_CHARS))
    }

    /** Last [MAX_TAIL_LINES] lines, for in-app display. */
    fun readTail(): String = runCatching { readTailLines(MAX_TAIL_LINES, MAX_TAIL_BYTES).joinToString("\n") }.getOrDefault("")

    /** Bounded content for export, including the rotated file when present. */
    fun readForExport(): String = runCatching {
        val dir = requireDir()
        val rotated = File(dir, ROTATED_FILE_NAME)
        buildString {
            if (rotated.isFile) {
                append(rotated.readText(Charsets.UTF_8).takeLast(MAX_EXPORT_BYTES.toInt()))
            }
            readTailLines(MAX_EXPORT_LINES, MAX_EXPORT_BYTES).forEach {
                append(it)
                append('\n')
            }
        }
    }.getOrDefault("")

    private fun append(
        level: Char,
        tag: String,
        message: String,
    ) {
        val timestamp = runCatching { requireNotNull(timestampFormatter.get()).format(Date()) }.getOrDefault("")
        writeRaw(
            formatLine(
                timestamp = timestamp,
                pid = Process.myPid(),
                thread = Thread.currentThread().name,
                level = level,
                tag = tag,
                message = message,
            ),
        )
    }

    private fun writeRaw(text: String) {
        val dir = logDir ?: return
        runCatching {
            synchronized(lock) {
                val file = File(dir, FILE_NAME)
                if (file.length() >= MAX_FILE_BYTES) rotate(dir, file)
                FileOutputStream(file, true).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    out.flush()
                }
            }
        }
    }

    private fun rotate(
        dir: File,
        file: File,
    ) {
        val rotated = File(dir, ROTATED_FILE_NAME)
        rotated.delete()
        if (!file.renameTo(rotated)) file.delete()
    }

    private fun readTailLines(
        maxLines: Int,
        maxBytes: Long,
    ): List<String> {
        val file = File(requireDir(), FILE_NAME)
        if (!file.isFile) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val buffer = ByteArray((length - start).toInt())
            raf.readFully(buffer)
            val lines = String(buffer, Charsets.UTF_8).split('\n')
            // When reading from the middle of the file the first line may be
            // partial; it carries no usable record.
            val usable = if (start > 0) lines.drop(1) else lines
            return usable.filter { it.isNotBlank() }.takeLast(maxLines)
        }
    }

    private fun installShutdownHook() {
        if (!shutdownHookInstalled.compareAndSet(false, true)) return
        runCatching {
            // Runs on a normal VM exit (including our own exitProcess). A
            // SIGKILL from the low-memory killer skips it, which is exactly
            // the distinction we want to record.
            Runtime.getRuntime().addShutdownHook(Thread { append('I', "process", PROCESS_EXIT_MARK) })
        }
    }

    private fun requireDir(): File = logDir ?: error("DiagnosticLog is not initialized")

    internal fun formatLine(
        timestamp: String,
        pid: Int,
        thread: String,
        level: Char,
        tag: String,
        message: String,
    ): String = "$timestamp $level [$pid $thread] $tag: $message"

    /**
     * Classify how the previous run of [processName] ended by inspecting the
     * lines that follow its most recent start marker. Other processes
     * (`:compile`) sharing this log are ignored.
     */
    internal fun classifyPreviousRun(
        lines: List<String>,
        processName: String,
    ): PreviousRun {
        val start = lines.indexOfLast { it.contains(PROCESS_START_MARK) && processNameOf(it) == processName }
        if (start < 0) return PreviousRun.NONE
        val tail = lines.subList(start + 1, lines.size)
        return when {
            tail.any { it.contains(CRASH_MARK) } -> PreviousRun.CRASH
            tail.any { it.contains(PROCESS_EXIT_MARK) } -> PreviousRun.CLEAN_EXIT
            else -> PreviousRun.UNKNOWN_KILLED
        }
    }

    /**
     * The `process=<name>` field of a process-start line. Matched field-wise
     * rather than by substring so `com.osfans.trime` does not also match
     * `com.osfans.trime:compile`.
     */
    internal fun processNameOf(line: String): String? = line
        .substringAfter(PROCESS_FIELD, "")
        .substringBefore(' ')
        .takeIf { it.isNotEmpty() }

    private const val PROCESS_FIELD = "process="
}
