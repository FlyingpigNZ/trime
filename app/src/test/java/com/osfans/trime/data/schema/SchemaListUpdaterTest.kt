// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files

class SchemaListUpdaterTest :
    StringSpec({
        "adds a new schema to the front of schema_list" {
            val customFile = File(Files.createTempDirectory("schema-list").toFile(), "default.custom.yaml")
            customFile.writeText(
                """
                patch:
                  schema_list:
                    - schema: luna_pinyin
                    - schema: luna_pinyin_simp
                """.trimIndent(),
            )

            SchemaListUpdater.addSchema(customFile, "14jian")

            val data = Yaml().load<Map<String, Any?>>(customFile.readText())
            val schemaList = (data["patch"] as Map<*, *>)["schema_list"] as List<*>
            schemaList.map { (it as Map<*, *>)["schema"] } shouldBe listOf("14jian", "luna_pinyin", "luna_pinyin_simp")
        }

        "moves an existing schema to the front without duplicating" {
            val customFile = File(Files.createTempDirectory("schema-list").toFile(), "default.custom.yaml")
            customFile.writeText(
                """
                patch:
                  schema_list:
                    - schema: luna_pinyin
                    - schema: 14jian
                """.trimIndent(),
            )

            SchemaListUpdater.addSchema(customFile, "14jian")

            val data = Yaml().load<Map<String, Any?>>(customFile.readText())
            val schemaList = (data["patch"] as Map<*, *>)["schema_list"] as List<*>
            schemaList.map { (it as Map<*, *>)["schema"] } shouldBe listOf("14jian", "luna_pinyin")
        }

        "creates default.custom.yaml when missing" {
            val customFile = File(Files.createTempDirectory("schema-list").toFile(), "default.custom.yaml")

            SchemaListUpdater.addSchema(customFile, "14jian")

            customFile.exists() shouldBe true
            val data = Yaml().load<Map<String, Any?>>(customFile.readText())
            val schemaList = (data["patch"] as Map<*, *>)["schema_list"] as List<*>
            schemaList.map { (it as Map<*, *>)["schema"] } shouldBe listOf("14jian")
        }
    })
