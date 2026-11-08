/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

@Suppress("unused")
class NativeCacheHashPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.tasks.register<NativeCacheHashCalcTask>("calculateNativeCacheHash") {
            group = "build"
            description =
                "Print the cache key of the prebuilt JNI libraries " +
                "(implemented by script/native-cache-hash.py)"
        }
    }

    /**
     * Convenience wrapper around script/native-cache-hash.py.
     *
     * The script owns the key format and CI calls it directly, so a job does
     * not have to configure the Android project (and let AGP install the NDK)
     * just to print a hash. Never re-implement the format here: two
     * implementations would eventually disagree and invalidate the
     * "app/prebuilt" cache.
     */
    abstract class NativeCacheHashCalcTask : DefaultTask() {
        @TaskAction
        fun execute() {
            val root = project.rootProject.projectDir
            val script = root.resolve("script/native-cache-hash.py")
            val process =
                ProcessBuilder("python3", script.absolutePath)
                    .directory(root)
                    .inheritIO()
                    .start()
            check(process.waitFor() == 0) {
                "script/native-cache-hash.py failed (exit ${process.exitValue()})"
            }
        }
    }
}
