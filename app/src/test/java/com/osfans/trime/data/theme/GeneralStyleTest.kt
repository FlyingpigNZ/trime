// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GeneralStyleTest :
    StringSpec({
        "decode applies defaults for empty style" {
            val style = decode("{}")

            style.autoCaps shouldBe false
            style.candidateBorder shouldBe 0
            style.candidateFont shouldBe emptyList()
            style.commentPosition shouldBe GeneralStyle.CommentPosition.RIGHT
            style.enterLabel.go shouldBe "go"
        }

        "decode reads explicit style fields" {
            val style =
                decode(
                    """
                    auto_caps: true
                    candidate_border: 3
                    candidate_font: [han.ttf, comment.ttf]
                    comment_position: top
                    enter_labels:
                      go: 前往
                    """.trimIndent(),
                )

            style.autoCaps shouldBe true
            style.candidateBorder shouldBe 3
            style.candidateFont shouldBe listOf("han.ttf", "comment.ttf")
            style.commentPosition shouldBe GeneralStyle.CommentPosition.TOP
            style.enterLabel.go shouldBe "前往"
        }
    })

private fun decode(yaml: String): GeneralStyle {
    val node = Yaml.Default.parseToYamlNode(yaml)
    require(node is Node.Mapping) { "expected a YAML mapping" }
    return GeneralStyle.decode(node)
}
