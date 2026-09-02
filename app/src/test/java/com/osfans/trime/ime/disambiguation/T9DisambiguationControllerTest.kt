// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.disambiguation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class T9DisambiguationControllerTest :
    StringSpec({
        "no declared keyboard -> any keyboard is eligible" {
            T9DisambiguationController.isEligibleKeyboard(null, "t9") shouldBe true
            T9DisambiguationController.isEligibleKeyboard("", "symbolscn_t9") shouldBe true
        }

        "declared keyboard equals current -> eligible" {
            T9DisambiguationController.isEligibleKeyboard("wanxiang_t9", "wanxiang_t9") shouldBe true
        }

        "declared keyboard differs from current -> not eligible" {
            // e.g. the schema declares the T9 keyboard, but the user switched to
            // the symbols keyboard: the panel must not overlay it.
            T9DisambiguationController.isEligibleKeyboard("wanxiang_t9", "symbolscn_t9") shouldBe false
            T9DisambiguationController.isEligibleKeyboard("wanxiang_t9", "letter_t9") shouldBe false
        }
    })
