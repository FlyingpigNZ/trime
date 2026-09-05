#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import yaml

from component_resolver import ComponentError, ComponentResolver


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class ComponentResolverTest(unittest.TestCase):
    def resolve(self, manifest: dict) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest_path = root / "manifest.yaml"
            write(manifest_path, yaml.safe_dump(manifest, allow_unicode=True))
            return ComponentResolver(root).resolve_manifest(manifest)

    def test_add_override_remove_layering(self) -> None:
        manifest = {
            "components": [
                {
                    "keyboard": {
                        "add": {
                            "qwerty": {"name": "base"},
                            "symbols": {"name": "base-symbols"},
                        }
                    }
                },
                {
                    "keyboard": {
                        "override": {"qwerty": {"name": "override", "width": 10}},
                        "remove": ["symbols"],
                        "add": {"14jian": {"name": "14键"}},
                    }
                },
            ]
        }
        sections = self.resolve(manifest)
        keyboards = sections["preset_keyboards"]
        self.assertEqual(keyboards["qwerty"], {"name": "override", "width": 10})
        self.assertNotIn("symbols", keyboards)
        self.assertEqual(keyboards["14jian"], {"name": "14键"})

    def test_style_field_override_merges(self) -> None:
        manifest = {
            "components": [
                {"style": {"override": {"keyboard_height": 200, "key_height": 40}}},
                {"style": {"override": {"key_height": 50}}},
            ]
        }
        sections = self.resolve(manifest)
        self.assertEqual(sections["style"]["keyboard_height"], 200)
        self.assertEqual(sections["style"]["key_height"], 50)

    def test_add_existing_fails(self) -> None:
        manifest = {
            "components": [
                {"keyboard": {"add": {"qwerty": {}}}},
                {"keyboard": {"add": {"qwerty": {}}}},
            ]
        }
        with self.assertRaises(ComponentError):
            self.resolve(manifest)

    def test_override_missing_fails(self) -> None:
        manifest = {
            "components": [
                {"keyboard": {"override": {"missing": {}}}},
            ]
        }
        with self.assertRaises(ComponentError):
            self.resolve(manifest)

    def test_remove_missing_fails(self) -> None:
        manifest = {
            "components": [
                {"keyboard": {"remove": ["missing"]}},
            ]
        }
        with self.assertRaises(ComponentError):
            self.resolve(manifest)

    def test_string_component_spec_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root / "color.yaml", "preset_color_schemes:\n  default:\n    light:\n      back_color: '#ffffff'\n")
            write(root / "style.yaml", "style:\n  keyboard_height: 200\n")
            manifest = {
                "components": [
                    {"color": "color.yaml"},
                    {"style": "style.yaml"},
                ]
            }
            sections = ComponentResolver(root).resolve_manifest(manifest)
            self.assertIn("default", sections["preset_color_schemes"])
            self.assertEqual(sections["style"]["keyboard_height"], 200)

    def test_unknown_color_palette_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(
                root / "color.yaml",
                "colors:\n  A:\n    back_color: '#ffffff'\ncolor_schemes:\n  Pair:\n    light: missing\n",
            )
            manifest = {"components": [{"color": {"file": "color.yaml"}}]}
            with self.assertRaises(ComponentError):
                ComponentResolver(root).resolve_manifest(manifest)

    def test_non_mapping_colors_entry_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(
                root / "color.yaml",
                "colors:\n  A: not-a-mapping\ncolor_schemes:\n  Pair:\n    light: A\n",
            )
            manifest = {"components": [{"color": {"file": "color.yaml"}}]}
            with self.assertRaises(ComponentError):
                ComponentResolver(root).resolve_manifest(manifest)

    def test_component_dir_include(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            shared = root / "local-aux"
            write(
                shared / "keyboard.yaml",
                "preset_keyboards:\n  aux1:\n    name: aux1\n",
            )
            write(
                shared / "behavior.yaml",
                "preset_keys:\n  BackSpace:\n    send: BackSpace\n",
            )
            manifest_path = root / "manifest.yaml"
            write(
                manifest_path,
                yaml.safe_dump(
                    {
                        "components": [
                            "local-aux",
                            {
                                "keyboard": {
                                    "override": {"aux1": {"name": "changed"}}
                                }
                            },
                        ]
                    },
                    allow_unicode=True,
                ),
            )
            sections = ComponentResolver(root).resolve_manifest(
                yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
            )
            self.assertEqual(sections["preset_keyboards"]["aux1"]["name"], "changed")
            self.assertEqual(
                sections["preset_keys"]["BackSpace"], {"send": "BackSpace"}
            )

if __name__ == "__main__":
    unittest.main()
