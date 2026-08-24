/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.CompileArtProfileTask
import com.android.build.gradle.internal.tasks.ExpandArtProfileWildcardsTask
import com.android.build.gradle.internal.tasks.MergeArtProfileTask
import com.android.build.gradle.tasks.PackageApplication
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.provider.AbstractProperty
import org.gradle.api.internal.provider.Providers
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

@Suppress("unused")
class AndroidAppConventionPlugin : AndroidBaseConventionPlugin() {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.application")

        super.apply(target)

        // remove META-INF/com/android/build/gradle/app-metadata.properties
        target.tasks.withType<PackageApplication> {
            val valueField =
                AbstractProperty::class.java.declaredFields.find { it.name == "value" } ?: run {
                    println("class AbstractProperty field value not found, something could have gone wrong")
                    return@withType
                }
            valueField.isAccessible = true
            doFirst {
                valueField.set(appMetadata, Providers.notDefined<RegularFile>())
                allInputFilesWithNameOnlyPathSensitivity.removeAll { true }
            }
        }

        // remove assets/dexopt/baseline.prof{,m} (baseline profile)
        target.tasks.withType<MergeArtProfileTask> { enabled = false }
        target.tasks.withType<ExpandArtProfileWildcardsTask> { enabled = false }
        target.tasks.withType<CompileArtProfileTask> { enabled = false }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            // Add dependency relationships for the asset generators that write
            // into app/src/main/assets (the source tree): lint's release
            // analysis scans that directory directly, so the relationship must
            // be declared explicitly or Gradle reports an implicit dependency
            // (and the lint result could be stale).
            onVariants { v ->
                val variantName = v.name.replaceFirstChar { it.uppercase() }
                // Evaluation should be delayed as we need be able to see other tasks
                target.afterEvaluate {
                    val assetGenerators =
                        listOfNotNull(
                            tasks.findByName(DataChecksumsPlugin.TASK),
                            tasks.findByName("buildDefaultPackage"),
                            tasks.findByName(OpenCCDataPlugin.INSTALL_TASK),
                        )
                    if (assetGenerators.isEmpty()) return@afterEvaluate
                    listOfNotNull(
                        tasks.findByName("merge${variantName}Assets"),
                        tasks.findByName("lintAnalyzeRelease"),
                        tasks.findByName("lintVitalAnalyzeRelease"),
                        tasks.findByName("generateReleaseLintVitalReportModel"),
                    ).forEach { consumer ->
                        assetGenerators.forEach { consumer.dependsOn(it) }
                    }
                }
            }
        }
    }
}
