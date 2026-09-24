// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.diagnostics

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.int
import com.osfans.trime.util.yaml.mapping
import java.io.File

/**
 * Snapshot of the facts librime's `DetectModifications` compares when it
 * decides whether an engine start needs a workspace deploy:
 *
 * `max(workspace dir mtime, shared dir mtime, top-level *.yaml mtimes)`
 * against the workspace's own `user.yaml` `var/last_build_time`.
 *
 * Only the active package's workspace is relevant: librime is handed exactly
 * one `user_data_dir` (plus the shared dir), so inactive packages never
 * participate. The rendered report is written to [com.osfans.trime.util.DiagnosticLog]
 * at deploy start so a "why did it deploy?" question can be answered after the
 * fact.
 */
object WorkspaceDiagnostics {
    internal const val MAX_REPORTED_ENTRIES = 20
    private const val USER_CONFIG_FILE = "user.yaml"
    private const val BUILD_TIME_PATH = "var/last_build_time"
    private const val PATH_SEPARATOR = '/'
    private const val MILLIS_PER_SECOND = 1000L
    private const val YAML_EXTENSION = ".yaml"

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val mtimeMs: Long,
    )

    data class DirSnapshot(
        val path: String,
        val mtimeMs: Long,
        val entries: List<Entry>,
    )

    data class DeployCause(
        val workspace: DirSnapshot,
        val shared: DirSnapshot,
        val lastBuildTimeSec: Long?,
    )

    fun capture(
        workspace: File,
        sharedDir: File,
    ): DeployCause = DeployCause(
        workspace = snapshot(workspace),
        shared = snapshot(sharedDir),
        lastBuildTimeSec = readLastBuildTime(workspace),
    )

    internal fun report(cause: DeployCause): List<String> = buildList {
        add("cause: baseline last_build_time=${cause.lastBuildTimeSec ?: "missing"}")
        add("cause: rule: root dir mtime, or top-level *.yaml except user.yaml")
        addAll(describeDir("ws", cause.workspace, cause.lastBuildTimeSec))
        addAll(describeDir("sh", cause.shared, cause.lastBuildTimeSec))
    }

    /** Pure: render one directory snapshot against the recorded build time. */
    internal fun describeDir(
        prefix: String,
        snapshot: DirSnapshot,
        lastBuildTimeSec: Long?,
    ): List<String> {
        val lines = mutableListOf<String>()
        val displayed = snapshot.entries.sortedByDescending { it.mtimeMs }.take(MAX_REPORTED_ENTRIES)
        val newerCount = snapshot.entries.count { isNewerThanBaseline(it.mtimeMs, lastBuildTimeSec) }
        val triggerCount =
            snapshot.entries.count { isTriggerCandidate(it) && isNewerThanBaseline(it.mtimeMs, lastBuildTimeSec) }
        lines +=
            "$prefix: path=${snapshot.path} mtimeMs=${snapshot.mtimeMs} " +
            "dirNewer=${isNewerThanBaseline(snapshot.mtimeMs, lastBuildTimeSec)} " +
            "entries=${snapshot.entries.size} newer=$newerCount triggers=$triggerCount"
        val baselineMs = lastBuildTimeSec?.times(MILLIS_PER_SECOND)
        displayed.forEach { entry ->
            val delta = baselineMs?.let { " delta=${(entry.mtimeMs - it) / MILLIS_PER_SECOND}s" }.orEmpty()
            val kind = if (entry.isDirectory) "dir " else "file"
            val trigger = isTriggerCandidate(entry) && isNewerThanBaseline(entry.mtimeMs, lastBuildTimeSec)
            val flag = if (trigger) " TRIGGER" else ""
            lines += "$prefix:   [$kind] ${entry.name} mtimeMs=${entry.mtimeMs}$delta$flag"
        }
        if (snapshot.entries.size > displayed.size) {
            lines += "$prefix:   ... ${snapshot.entries.size - displayed.size} more entries omitted"
        }
        return lines
    }

    /**
     * Whether an entry is something librime's `DetectModifications` actually
     * reads: only the directory's own mtime (reported separately) and
     * top-level regular `*.yaml` files other than `user.yaml`. Directory
     * mtimes (`*.userdb/`) and other files (`compiled.marker`) are shown for
     * context but never trigger a deploy.
     */
    internal fun isTriggerCandidate(entry: Entry): Boolean = !entry.isDirectory && entry.name.endsWith(YAML_EXTENSION) && entry.name != USER_CONFIG_FILE

    /**
     * Same second-resolution comparison librime uses (`last_modified >
     * last_build_time`, both whole seconds). A missing baseline counts as
     * "everything is newer", which is what makes the deploy unconditional.
     */
    internal fun isNewerThanBaseline(
        mtimeMs: Long,
        lastBuildTimeSec: Long?,
    ): Boolean = lastBuildTimeSec == null || mtimeMs / MILLIS_PER_SECOND > lastBuildTimeSec

    private fun snapshot(dir: File): DirSnapshot = DirSnapshot(
        path = dir.absolutePath,
        mtimeMs = dir.lastModified(),
        entries = dir.listFiles()?.map { Entry(it.name, it.isDirectory, it.lastModified()) }.orEmpty(),
    )

    private fun readLastBuildTime(workspace: File): Long? = runCatching {
        val config = File(workspace, USER_CONFIG_FILE)
        if (!config.isFile) return null
        var node: Node? = Yaml.Default.parseToYamlNode(config.readText(Charsets.UTF_8))
        BUILD_TIME_PATH.split(PATH_SEPARATOR).forEach { key ->
            node = node?.mapping?.get(key)
        }
        node?.int?.toLong()
    }.getOrNull()
}
