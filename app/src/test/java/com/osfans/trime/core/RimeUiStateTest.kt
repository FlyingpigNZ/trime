// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RimeUiStateTest :
    StringSpec({
        "defaults represent the initial non-composing ascii state" {
            val state = RimeUiState()

            state.schemaId shouldBe ""
            state.schemaName shouldBe ""
            state.isAsciiMode shouldBe true
            state.isAsciiPunct shouldBe true
            state.isComposing shouldBe false
            state.hasMenu shouldBe false
            state.paging shouldBe false
            state.options shouldBe emptyMap()
        }

        "derived accessors read from the status snapshot" {
            val state =
                RimeUiState(
                    status =
                        StatusProto(
                            schemaId = "luna_pinyin",
                            schemaName = "朙月拼音",
                            isAsciiMode = false,
                            isAsciiPunct = false,
                            isComposing = true,
                        ),
                    hasMenu = true,
                    paging = true,
                    options = mapOf("_hide_key_symbol" to true),
                )

            state.schemaId shouldBe "luna_pinyin"
            state.schemaName shouldBe "朙月拼音"
            state.isAsciiMode shouldBe false
            state.isAsciiPunct shouldBe false
            state.isComposing shouldBe true
            state.hasMenu shouldBe true
            state.paging shouldBe true
            state.options.getValue("_hide_key_symbol") shouldBe true
        }
    })
