#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import yaml

import generate_manifest


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class GenerateManifestTest(unittest.TestCase):
    def test_component_types_follow_definition_schema(self) -> None:
        """Root definition files map to the doc/definition-schema.md types."""
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp)
            for name in (
                "keyboard.yaml",
                "behavior.yaml",
                "color.yaml",
                "style.yaml",
                "chrome.yaml",
                "liquid_keyboard.yaml",
                "my.schema.yaml",
            ):
                write(src / name, "key: value\n")

            self.assertEqual(
                generate_manifest.build_components(src),
                [
                    {"behavior": {"file": "behavior.yaml"}},
                    {"style": {"file": "chrome.yaml"}},
                    {"color": {"file": "color.yaml"}},
                    {"keyboard": {"file": "keyboard.yaml"}},
                    {"style": {"file": "liquid_keyboard.yaml"}},
                    {"schema": {"file": "my.schema.yaml"}},
                    {"style": {"file": "style.yaml"}},
                ],
            )

    def test_unknown_root_definition_file_is_warned_and_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp)
            write(src / "keyboard.yaml", "preset_keyboards:\n  default:\n    name: default\n")
            write(src / "layout.yaml", "name: My\nstyle: {}\n")

            with self.assertLogs(level="WARNING") as logs:
                components = generate_manifest.build_components(src)

            self.assertEqual(components, [{"keyboard": {"file": "keyboard.yaml"}}])
            self.assertTrue(
                any("layout.yaml" in line for line in logs.output),
                f"expected a warning about layout.yaml, got: {logs.output}",
            )

    def test_rime_files_are_sorted_relative_paths(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp)
            write(src / "rime" / "b.yaml", "b\n")
            write(src / "rime" / "cn_dicts" / "a.dict.yaml", "a\n")
            write(src / "rime" / "lua" / "x.lua", "x\n")
            # Non-rime root files must not appear in rime_files.
            write(src / "keyboard.yaml", "preset_keyboards:\n  default:\n    name: default\n")

            self.assertEqual(
                generate_manifest.build_rime_files(src),
                [
                    "rime/b.yaml",
                    "rime/cn_dicts/a.dict.yaml",
                    "rime/lua/x.lua",
                ],
            )

    def test_manifest_keeps_metadata_and_writes_only_known_sections(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp)
            write(src / "manifest.yaml", "name: My IME\nunknown_key: keep-me\n")
            write(src / "my.schema.yaml", "schema:\n  schema_id: my\n")
            write(src / "keyboard.yaml", "preset_keyboards:\n  default:\n    name: default\n")

            self.assertEqual(generate_manifest.main([str(src)]), 0)

            manifest = yaml.safe_load((src / "manifest.yaml").read_text(encoding="utf-8"))
            self.assertEqual(manifest["name"], "My IME")
            self.assertNotIn("unknown_key", manifest)
            self.assertEqual(
                manifest["components"],
                [
                    {"keyboard": {"file": "keyboard.yaml"}},
                    {"schema": {"file": "my.schema.yaml"}},
                ],
            )
            self.assertEqual(manifest["rime_files"], [])

    def test_new_package_derives_metadata_from_first_schema(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "my-package"
            write(src / "my.schema.yaml", "schema:\n  schema_id: my\n")
            write(src / "style.yaml", "style:\n  keyboard_height: 48\n")

            self.assertEqual(generate_manifest.main([str(src)]), 0)

            manifest = yaml.safe_load((src / "manifest.yaml").read_text(encoding="utf-8"))
            self.assertEqual(manifest["name"], "my-package")
            self.assertEqual(manifest["schema_id"], "my")
            self.assertEqual(manifest["default_keyboard"], "my")
            self.assertEqual(
                manifest["components"],
                [
                    {"schema": {"file": "my.schema.yaml"}},
                    {"style": {"file": "style.yaml"}},
                ],
            )

    def test_missing_directory_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(generate_manifest.main([str(Path(tmp) / "nope")]), 1)


if __name__ == "__main__":
    unittest.main()
