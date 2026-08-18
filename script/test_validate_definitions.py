#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

_SCRIPT = Path(__file__).resolve().parent / "validate-definitions.py"
_spec = importlib.util.spec_from_file_location("validate_definitions_cli", _SCRIPT)
_validate_definitions = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_validate_definitions)
validate_component_manifest = _validate_definitions.validate_component_manifest


class ValidateDefinitionsTest(unittest.TestCase):
    def validate(self, components: list) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            return validate_component_manifest(
                {"components": components},
                Path(tmp) / "manifest.yaml",
            )

    def test_valid_manifest_passes(self) -> None:
        errors = self.validate(
            [
                {"style": {"override": {"keyboard_height": 200}}},
                {
                    "color": {
                        "add": {
                            "default": {
                                "light": {"back_color": "#ffffff"},
                            }
                        }
                    }
                },
            ]
        )
        self.assertEqual(errors, [])

    def test_missing_style_fails(self) -> None:
        errors = self.validate(
            [
                {
                    "color": {
                        "add": {
                            "default": {
                                "light": {"back_color": "#ffffff"},
                            }
                        }
                    }
                }
            ]
        )
        self.assertIn(
            "Component package must define a non-empty 'style' section",
            errors,
        )

    def test_missing_color_schemes_fails(self) -> None:
        errors = self.validate(
            [
                {"style": {"override": {"keyboard_height": 200}}},
            ]
        )
        self.assertIn(
            "Component package must define at least one 'preset_color_schemes' entry",
            errors,
        )


if __name__ == "__main__":
    unittest.main()
