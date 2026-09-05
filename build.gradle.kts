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
    kotlin {
        target("**/*.kt", "**/*.kts")
        targetExclude(
            // Generated / caches must not be formatted: build outputs and any
            // GRADLE_USER_HOME variant checked out into the workspace (see
            // .gitignore and doc/repo-knowledge.md §9.2).
            "build/**",
            "**/build/**",
            ".gradle/**",
            ".gradle-home/**",
            ".gradle-test-home/**",
            ".gradle-home-fix/**",
        )
        ktlint("1.7.1")
    }
}
