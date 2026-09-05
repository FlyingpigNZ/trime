// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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

        "setSchemas replaces the previous schema list" {
            val customFile = File(Files.createTempDirectory("schema-list").toFile(), "default.custom.yaml")
            customFile.writeText(
                """
                patch:
                  schema_list:
                    - schema: luna_pinyin
                    - schema: 14jian
                """.trimIndent(),
            )

            SchemaListUpdater.setSchemas(customFile, listOf("14jian", "melt_eng", "radical_pinyin"))

            val data = Yaml().load<Map<String, Any?>>(customFile.readText())
            val schemaList = (data["patch"] as Map<*, *>)["schema_list"] as List<*>
            schemaList.map { (it as Map<*, *>)["schema"] } shouldBe
                listOf("14jian", "melt_eng", "radical_pinyin")
        }

        "creates default.custom.yaml when missing" {
            val customFile = File(Files.createTempDirectory("schema-list").toFile(), "default.custom.yaml")

            SchemaListUpdater.addSchema(customFile, "14jian")

            customFile.exists() shouldBe true
            val data = Yaml().load<Map<String, Any?>>(customFile.readText())
            val schemaList = (data["patch"] as Map<*, *>)["schema_list"] as List<*>
            schemaList.map { (it as Map<*, *>)["schema"] } shouldBe listOf("14jian")
        }

        "setSchemas preserves comments and unrelated patch keys" {
            val customFile = File(Files.createTempDirectory("schema-list").toFile(), "default.custom.yaml")
            customFile.writeText(
                """
                # 我的输入方案
                patch:
                  # 下面列出启用的方案
                  schema_list:
                    - schema: luna_pinyin
                    - schema: 14jian
                  menu:
                    page_size: 9
                """.trimIndent() + "\n",
            )

            SchemaListUpdater.setSchemas(customFile, listOf("14jian", "melt_eng"))

            val text = customFile.readText()
            text shouldContain "# 我的输入方案"
            text shouldContain "# 下面列出启用的方案"
            text shouldContain "page_size: 9"
            val data = Yaml().load<Map<String, Any?>>(text)
            val schemaList = (data["patch"] as Map<*, *>)["schema_list"] as List<*>
            schemaList.map { (it as Map<*, *>)["schema"] } shouldBe listOf("14jian", "melt_eng")
        }

        "inserts schema_list when patch has no schema_list yet" {
            val customFile = File(Files.createTempDirectory("schema-list").toFile(), "default.custom.yaml")
            customFile.writeText(
                """
                patch:
                  menu:
                    page_size: 9
                # 文件末尾注释
                """.trimIndent() + "\n",
            )

            SchemaListUpdater.setSchemas(customFile, listOf("14jian"))

            val text = customFile.readText()
            text shouldContain "# 文件末尾注释"
            val data = Yaml().load<Map<String, Any?>>(text)
            val schemaList = (data["patch"] as Map<*, *>)["schema_list"] as List<*>
            schemaList.map { (it as Map<*, *>)["schema"] } shouldBe listOf("14jian")
            (data["patch"] as Map<*, *>)["menu"] shouldNotBe null
        }

        "falls back for flow-style patch layout" {
            val customFile = File(Files.createTempDirectory("schema-list").toFile(), "default.custom.yaml")
            customFile.writeText("patch: {}\n")

            SchemaListUpdater.setSchemas(customFile, listOf("14jian"))

            val data = Yaml().load<Map<String, Any?>>(customFile.readText())
            val schemaList = (data["patch"] as Map<*, *>)["schema_list"] as List<*>
            schemaList.map { (it as Map<*, *>)["schema"] } shouldBe listOf("14jian")
        }
    })
