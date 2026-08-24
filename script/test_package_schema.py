#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

import package_schema


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class PackageSchemaTest(unittest.TestCase):
    def test_component_package_includes_all_local_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "my-ime"
            write(
                pkg / "manifest.yaml",
                """name: My IME
components:
  - keyboard:
      file: keyboard.yaml
""",
            )
            write(pkg / "my.schema.yaml", "schema:\n  schema_id: my\n")
            write(pkg / "keyboard.yaml", "preset_keyboards:\n  default:\n    name: default\n")

            self.assertEqual(
                package_schema.main([str(pkg)]),
                0,
            )

            with zipfile.ZipFile(pkg.with_suffix(".zip")) as z:
                names = z.namelist()
                self.assertIn("manifest.yaml", names)
                self.assertIn("keyboard.yaml", names)
                self.assertIn("my.schema.yaml", names)

    def test_rime_files_are_pulled_from_source_and_packaged(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "my-ime"
            rime = root / "rime.雾凇"
            write(
                pkg / "manifest.yaml",
                """name: My IME
components:
  - keyboard:
      file: keyboard.yaml
rime_files:
  - rime/default.yaml
  - rime/cn_dicts/base.dict.yaml
""",
            )
            write(pkg / "keyboard.yaml", "preset_keyboards:\n  default:\n    name: default\n")
            write(rime / "default.yaml", "config_version: 'test'\n")
            write(rime / "cn_dicts/base.dict.yaml", "name: base\n")

            self.assertEqual(
                package_schema.main([str(pkg)]),
                0,
            )

            with zipfile.ZipFile(pkg.with_suffix(".zip")) as z:
                self.assertEqual(
                    z.read("rime/default.yaml").decode("utf-8"),
                    "config_version: 'test'\n",
                )
                self.assertEqual(
                    z.read("rime/cn_dicts/base.dict.yaml").decode("utf-8"),
                    "name: base\n",
                )

    def test_missing_rime_source_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "my-ime"
            write(
                pkg / "manifest.yaml",
                """name: My IME
components: []
rime_files: [rime/default.yaml]
""",
            )

            self.assertEqual(
                package_schema.main([str(pkg)]),
                1,
            )

    def test_non_component_manifest_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "old-schema"
            write(
                pkg / "manifest.yaml",
                """schema_id: my
name: Old
version: "1"
schema_file: my.schema.yaml
layout_files: [my.layout.yaml]
""",
            )
            write(pkg / "my.schema.yaml", "schema:\n  schema_id: my\n")
            write(pkg / "my.layout.yaml", "name: My\nstyle: {}\n")

            self.assertEqual(
                package_schema.main([str(pkg)]),
                1,
            )


    def test_schema_list_referencing_unshipped_schema_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "my-ime"
            write(
                pkg / "manifest.yaml",
                """name: My IME
components:
  - keyboard:
      file: keyboard.yaml
rime_files:
  - rime/default.yaml
  - rime/my.schema.yaml
""",
            )
            write(
                pkg / "rime" / "default.yaml",
                """schema_list:
  - schema: my
  - schema: bopomofo
""",
            )
            write(pkg / "rime" / "my.schema.yaml", "schema:\n  schema_id: my\n")
            write(pkg / "keyboard.yaml", "preset_keyboards:\n  default:\n    name: default\n")

            # A schema_list entry without a shipped .schema.yaml would make
            # librime's workspace_update fail the whole deploy.
            self.assertEqual(
                package_schema.main([str(pkg)]),
                1,
            )

            # Fixing the list to only shipped schemas packages cleanly.
            write(
                pkg / "rime" / "default.yaml",
                "schema_list:\n  - schema: my\n",
            )
            self.assertEqual(
                package_schema.main([str(pkg)]),
                0,
            )


if __name__ == "__main__":
    unittest.main()
