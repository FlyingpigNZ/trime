// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.data.theme.DefinitionValidator
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string

/**
 * Unified validation for a component manifest and its referenced component
 * files.
 *
 * Checks:
 *  1. per-file structure (keyboard / behavior / color / style / resources)
 *  2. component resolution semantics (add/override/remove/include)
 *  3. cross-reference validation (behavior verifier)
 *  4. color literal validation
 */
object ComponentValidator {
    private val PRESET_KEY_ACTIONS =
        setOf("send", "text", "commit", "command", "toggle", "select")
    private val STYLE_FILE_KEYS =
        setOf("style", "preedit", "window", "tool_bar", "liquid_keyboard")

    fun validate(
        manifestYaml: String,
        source: ComponentSource,
    ): List<String> {
        val node =
            Yaml.Default.parseToYamlNode(manifestYaml).mapping
                ?: return listOf("Component manifest must be a YAML mapping")
        val errors = mutableListOf<String>()
        val manifest =
            try {
                ComponentManifest.parse(node)
            } catch (e: Exception) {
                return listOf(e.message ?: "Invalid component manifest")
            }

        errors += validateReferencedFiles(manifest, source)

        val sections =
            try {
                ComponentResolver(source).resolve(manifest)
            } catch (e: Exception) {
                errors += e.message ?: "Invalid component resolution"
                return errors
            }

        errors += BehaviorVerifier.verify(sections)
        errors += DefinitionValidator.validateColorLiterals(sections)
        return errors
    }

    // ── per-file validation ───────────────────────────────────────────────
    private fun validateReferencedFiles(
        manifest: ComponentManifest,
        source: ComponentSource,
    ): List<String> {
        val errors = mutableListOf<String>()
        manifest.components.forEach { entry ->
            when (entry) {
                is ComponentManifest.ComponentEntry.Reference -> {
                    if (entry.name != "standard") {
                        errors += validateDirectory(entry.name, source)
                    }
                }
                is ComponentManifest.ComponentEntry.Schema -> Unit
                is ComponentManifest.ComponentEntry.Keyboard ->
                    entry.spec.files.forEach { file ->
                        runCatching { source.load(file) }
                            .onSuccess { errors += validateKeyboardFile(it, file) }
                            .onFailure { errors += it.message ?: "Failed to load $file" }
                    }
                is ComponentManifest.ComponentEntry.Behavior ->
                    entry.spec.files.forEach { file ->
                        runCatching { source.load(file) }
                            .onSuccess { errors += validateBehaviorFile(it, file) }
                            .onFailure { errors += it.message ?: "Failed to load $file" }
                    }
                is ComponentManifest.ComponentEntry.Color ->
                    entry.spec.files.forEach { file ->
                        runCatching { source.load(file) }
                            .onSuccess { errors += validateColorFile(it, file) }
                            .onFailure { errors += it.message ?: "Failed to load $file" }
                    }
                is ComponentManifest.ComponentEntry.Style ->
                    entry.spec.files.forEach { file ->
                        runCatching { source.load(file) }
                            .onSuccess { errors += validateStyleFile(it, file) }
                            .onFailure { errors += it.message ?: "Failed to load $file" }
                    }
                is ComponentManifest.ComponentEntry.Resources ->
                    entry.spec.files.forEach { file ->
                        runCatching { source.load(file) }
                            .onSuccess { errors += validateResourcesFile(it, file) }
                            .onFailure { errors += it.message ?: "Failed to load $file" }
                    }
            }
        }
        return errors
    }

    private fun validateDirectory(
        directory: String,
        source: ComponentSource,
    ): List<String> {
        val errors = mutableListOf<String>()
        listOf(
            "keyboard.yaml" to ::validateKeyboardFile,
            "behavior.yaml" to ::validateBehaviorFile,
            "color.yaml" to ::validateColorFile,
            "style.yaml" to ::validateStyleFile,
        ).forEach { (file, validator) ->
            val path = "$directory/$file"
            runCatching { source.load(path) }
                .onSuccess { errors += validator(it, path) }
                .onFailure { /* optional file missing is fine */ }
        }
        return errors
    }

    private fun validateKeyboardFile(data: Node.Mapping, path: String): List<String> {
        val errors = mutableListOf<String>()
        val keyboards = data["preset_keyboards"]?.mapping
            ?: return listOf("$path: missing 'preset_keyboards'")
        keyboards.pairs.forEach { (nameNode, valueNode) ->
            val name = nameNode.string ?: return@forEach
            val keyboard = valueNode.mapping
                ?: run { errors += "$path.preset_keyboards.$name: must be a mapping"; return@forEach }
            if (keyboard["name"]?.string.isNullOrEmpty() && keyboard["__include"]?.string.isNullOrEmpty()) {
                errors += "$path.preset_keyboards.$name: missing 'name' or '__include'"
            }
            val keys = keyboard["keys"]?.sequence?.nodes ?: emptyList()
            keys.forEachIndexed { index, keyNode ->
                val key = keyNode.mapping
                    ?: run { errors += "$path.preset_keyboards.$name.keys[$index]: must be a mapping"; return@forEachIndexed }
                if (key.pairs.isEmpty()) {
                    errors += "$path.preset_keyboards.$name.keys[$index]: key mapping must not be empty"
                }
            }
        }
        return errors
    }

    private fun validateBehaviorFile(data: Node.Mapping, path: String): List<String> {
        val errors = mutableListOf<String>()
        data["preset_keys"]?.mapping?.pairs?.forEach { (nameNode, valueNode) ->
            val name = nameNode.string ?: return@forEach
            val key = valueNode.mapping
                ?: run { errors += "$path.preset_keys.$name: must be a mapping"; return@forEach }
            val hasAction = PRESET_KEY_ACTIONS.any { key[it] != null }
            if (!hasAction) {
                errors += "$path.preset_keys.$name: missing an action (send/text/commit/command/toggle/select)"
            }
        }
        return errors
    }

    private fun validateColorFile(data: Node.Mapping, path: String): List<String> {
        val errors = mutableListOf<String>()
        data["preset_color_schemes"]?.mapping?.pairs?.forEach { (nameNode, schemeNode) ->
            val name = nameNode.string ?: return@forEach
            val scheme = schemeNode.mapping
                ?: run { errors += "$path.preset_color_schemes.$name: must be a mapping"; return@forEach }
            val palettes = mutableListOf<Node.Mapping>()
            scheme["light"]?.mapping?.let { palettes += it }
            scheme["dark"]?.mapping?.let { palettes += it }
            if (palettes.isEmpty()) palettes += scheme
            palettes.forEach { palette ->
                palette.pairs.forEach { (keyNode, valueNode) ->
                    val key = keyNode.string ?: return@forEach
                    if (key == "name" || key == "author") return@forEach
                    val value = valueNode.string ?: return@forEach
                    if (!isHexColor(value)) {
                        errors += "$path.preset_color_schemes.$name.$key: invalid color '$value'"
                    }
                }
            }
        }
        return errors
    }

    private fun validateStyleFile(data: Node.Mapping, path: String): List<String> {
        val unknown = data.pairs.keys.mapNotNull { it.string }.filter { it !in STYLE_FILE_KEYS }
        if (unknown.isNotEmpty()) {
            return listOf("$path: unknown style file keys: ${unknown.joinToString()}")
        }
        return emptyList()
    }

    private fun validateResourcesFile(data: Node.Mapping, path: String): List<String> {
        if (data["resources"]?.sequence == null) {
            return listOf("$path: missing 'resources' list")
        }
        return emptyList()
    }

    private fun isHexColor(value: String): Boolean {
        val body =
            when {
                value.startsWith("0x", ignoreCase = true) -> value.substring(2)
                value.startsWith("#") -> value.substring(1)
                else -> return false
            }
        return body.length in 1..8 && body.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    }
}
