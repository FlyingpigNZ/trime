#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Equivalence tests: component-assembled definitions vs monolithic baselines.

The baseline is the original monolith's own definitions plus the standard
components that the new model intentionally selects:
  - use_standard_preset_keys: true adds all standard preset_keys
  - standard_keyboards: [default, letter, number, symbols]
  - standard_color_schemes: [default]

For 简纯+14键 the baseline uses the already-normalized theme.yaml plus the
schema layout, because the raw monolith still contains conf/styl/__patch.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "script"))

from component_resolver import resolve_file  # noqa: E402


def load(path: str) -> dict:
    return yaml.safe_load((ROOT / path).read_text(encoding="utf-8"))


def merge_dicts(*dicts: dict) -> dict:
    out: dict = {}
    for d in dicts:
        out.update(d or {})
    return out


class EquivalenceTest(unittest.TestCase):
    def assertSectionsEqual(self, resolved: dict, expected: dict) -> None:
        for section in (
            "preset_keys",
            "preset_keyboards",
            "preset_color_schemes",
            "fallback_colors",
            "style",
            "liquid_keyboard",
            "preedit",
            "window",
            "tool_bar",
        ):
            self.assertEqual(
                resolved.get(section, {}),
                expected.get(section, {}),
                f"section '{section}' differs",
            )

    def test_tongwenfeng_equivalence(self) -> None:
        resolved = resolve_file(ROOT / "sample_theme_schemas/tongwenfeng/manifest.yaml")
        mono = load("app/src/main/assets/shared/tongwenfeng.trime.yaml")
        standard_keys = load("app/src/main/assets/shared/standard/preset_keys.yaml")["preset_keys"]

        expected = {
            "preset_keys": merge_dicts(standard_keys, mono.get("preset_keys")),
            "preset_keyboards": mono.get("preset_keyboards", {}),
            "preset_color_schemes": mono.get("preset_color_schemes", {}),
            "fallback_colors": mono.get("fallback_colors", {}),
            "style": mono.get("style", {}),
            "liquid_keyboard": mono.get("liquid_keyboard", {}),
            "preedit": mono.get("preedit", {}),
            "window": mono.get("window", {}),
            "tool_bar": mono.get("tool_bar", {}),
        }
        self.assertSectionsEqual(resolved, expected)

    def test_jian_equivalence(self) -> None:
        resolved = resolve_file(ROOT / "sample_theme_schemas/简纯+14键/component.yaml")
        theme = load("sample_theme_schemas/简纯+14键/theme.yaml")
        layout = load("sample_theme_schemas/简纯+14键/14jian.layout.yaml")
        standard_keys = load("app/src/main/assets/shared/standard/preset_keys.yaml")["preset_keys"]

        expected = {
            "preset_keys": merge_dicts(
                standard_keys,
                theme.get("preset_keys"),
                layout.get("preset_keys"),
            ),
            "preset_keyboards": merge_dicts(
                theme.get("preset_keyboards"),
                layout.get("preset_keyboards"),
            ),
            "preset_color_schemes": theme.get("preset_color_schemes", {}),
            "fallback_colors": theme.get("fallback_colors", {}),
            "style": theme.get("style", {}),
            "liquid_keyboard": theme.get("liquid_keyboard", {}),
            "preedit": {},
            "window": {},
            "tool_bar": {},
        }
        self.assertSectionsEqual(resolved, expected)


if __name__ == "__main__":
    unittest.main()
