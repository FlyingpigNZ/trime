#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

from __future__ import annotations

import unittest

from behavior_verifier import verify


class BehaviorVerifierTest(unittest.TestCase):
    def test_valid_definitions_pass(self) -> None:
        sections = {
            "preset_keys": {
                "space": {"label": "空格", "send": "space"},
                "Keyboard_number": {"label": "123", "send": "Eisu_toggle", "select": "number"},
                "Punct_switch": {"toggle": "ascii_punct", "states": ["。，", "．，"]},
            },
            "preset_keyboards": {
                "default": {
                    "ascii_keyboard": "letter",
                    "keys": [
                        {"click": "space"},
                        {"click": "a"},
                        {"click": "Keyboard_number"},
                        {"click": "Punct_switch"},
                        {"click": 1},
                    ],
                },
                "letter": {"keys": []},
                "number": {"keys": []},
            },
            "keyboard_switch_policy": {
                "default_keyboard": "default",
                "ascii_keyboard": "letter",
                "aux": {"number": "number"},
            },
        }
        self.assertEqual(verify(sections), [])

    def test_missing_ascii_keyboard_fails(self) -> None:
        sections = {
            "preset_keys": {},
            "preset_keyboards": {
                "default": {"ascii_keyboard": "missing", "keys": []},
            },
            "keyboard_switch_policy": {},
        }
        errors = verify(sections)
        self.assertTrue(any("ascii_keyboard" in e and "missing" in e for e in errors))

    def test_select_unknown_keyboard_fails(self) -> None:
        sections = {
            "preset_keys": {
                "Bad": {"send": "Eisu_toggle", "select": "missing"},
            },
            "preset_keyboards": {
                "default": {"keys": [{"click": "Bad"}]},
            },
            "keyboard_switch_policy": {},
        }
        errors = verify(sections)
        self.assertTrue(any("select' references unknown keyboard" in e for e in errors))

    def test_toggle_without_states_fails(self) -> None:
        sections = {
            "preset_keys": {
                "Bad": {"toggle": "ascii_mode"},
            },
            "preset_keyboards": {
                "default": {"keys": [{"click": "Bad"}]},
            },
            "keyboard_switch_policy": {},
        }
        errors = verify(sections)
        self.assertTrue(any("states" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
