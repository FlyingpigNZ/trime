// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RimeMessageTest :
    StringSpec({
        "nativeCreate type 1 builds a SchemaMessage" {
            val message =
                RimeMessage.nativeCreate(1, arrayOf<Any>("luna_pinyin/朙月拼音"))

            message shouldBe RimeMessage.SchemaMessage(SchemaItem("luna_pinyin", "朙月拼音"))
        }

        "nativeCreate type 2 parses option on/off markers" {
            val on =
                RimeMessage.nativeCreate(2, arrayOf<Any>("ascii_mode"))
            on shouldBe RimeMessage.OptionMessage(RimeMessage.OptionMessage.Data("ascii_mode", true))

            val off =
                RimeMessage.nativeCreate(2, arrayOf<Any>("!ascii_mode"))
            off shouldBe RimeMessage.OptionMessage(RimeMessage.OptionMessage.Data("ascii_mode", false))
        }

        "nativeCreate type 3 maps deploy states" {
            RimeMessage.nativeCreate(3, arrayOf<Any>("start")) shouldBe
                RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Start)
            RimeMessage.nativeCreate(3, arrayOf<Any>("success")) shouldBe
                RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Success)
            RimeMessage.nativeCreate(3, arrayOf<Any>("failure")) shouldBe
                RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Failure)
        }

        "nativeCreate unknown types fall back to UnknownMessage" {
            val params = arrayOf<Any>("unexpected", 42)
            val message = RimeMessage.nativeCreate(0, params)

            message shouldBe RimeMessage.UnknownMessage(params)
            message.messageType shouldBe RimeMessage.MessageType.Unknown
        }
    })
