// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * The buildscript block is where you configure the repositories and
 * dependencies for Gradle itself--meaning, you should not include dependencies
 * for your modules here. For example, this block includes the Android plugin for
 * Gradle as a dependency because it provides the additional instructions Gradle
 * needs to build Android app modules.
 */

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.spotless)
}

spotless {
    // Explicit line endings instead of the default git-attributes handling:
    // GitAttributesLineEndings walks the whole working tree on every run,
    // which is unusably slow here (vendored jni/boost alone has ~59k files).
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        // Scope to real Kotlin source roots instead of repo-wide ** globs:
        // matching **/*.kt forces Gradle to walk the whole working tree,
        // including the vendored app/src/main/jni/boost subtree.
        target(
            "app/src/*/java/**/*.kt",
            "codegen/src/*/java/**/*.kt",
            "codegen/src/*/kotlin/**/*.kt",
            "build-logic/**/*.kt",
            "*.kts",
            "app/*.kts",
            "codegen/*.kts",
            "build-logic/**/*.kts",
        )
        ktlint("1.7.1")
    }
}
