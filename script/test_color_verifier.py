#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

from __future__ import annotations

import unittest

from color_verifier import verify


class ColorVerifierTest(unittest.TestCase):
    def test_cycle_is_rejected(self) -> None:
        errors = verify(
            {
                "fallback_colors": {
                    "a": "b",
                    "b": "a",
                },
            }
        )
        self.assertTrue(any("cycle detected: a -> b -> a" in error for error in errors))

    def test_references_are_order_independent_and_accept_builtin_keys(self) -> None:
        sections = {
            "preset_color_schemes": {
                "default": {
                    "light": {"back_color": "#ffffff"},
                },
            },
            "fallback_colors": {
                "a": "b",
                "b": "candidate_text_color",
            },
        }
        self.assertEqual(verify(sections), [])

    def test_missing_builtin_key_is_rejected(self) -> None:
        errors = verify(
            {
                "fallback_colors": {
                    "a": "not_a_real_color_key",
                },
            }
        )
        self.assertTrue(any("invalid color reference" in error for error in errors))

    def test_drawable_background_keys_accept_image_filenames(self) -> None:
        sections = {
            "preset_color_schemes": {
                "default": {
                    "light": {
                        "back_color": "#ffffff",
                        "keyboard_background": "default.jpg",
                    },
                    "dark": {
                        "back_color": "#000000",
                        "keyboard_background": "dark_temple.jpg",
                    },
                },
            },
        }
        self.assertEqual(verify(sections), [])

    def test_zero_alpha_8digit_hex_is_rejected(self) -> None:
        # 0x00141617 parses as AARRGGBB with alpha 0x00 — fully transparent,
        # almost certainly a reversed-alpha typo.
        errors = verify(
            {
                "preset_color_schemes": {
                    "s": {
                        "light": {"hilited_back_color": "0x00141617"},
                    },
                },
            }
        )
        self.assertTrue(any("alpha first" in error for error in errors))

    def test_zero_alpha_8digit_hex_allows_explicit_transparent(self) -> None:
        # 0x00000000 is the documented "no shadow / no tint" value.
        sections = {
            "preset_color_schemes": {
                "s": {
                    "light": {
                        "hilited_back_color": "0x00000000",
                        "shadow_color": "0x00000000",
                    },
                },
            },
        }
        self.assertEqual(verify(sections), [])


if __name__ == "__main__":
    unittest.main()
