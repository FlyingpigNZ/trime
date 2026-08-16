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
    def test_rime_files_are_pulled_from_source_and_packaged(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "my-schema"
            rime = root / "rime.雾凇"
            write(
                pkg / "manifest.yaml",
                """schema_id: my
name: My Schema
version: "1"
schema_file: my.schema.yaml
theme_file: theme.yaml
layout_files: [my.layout.yaml]
rime_files:
  - rime/default.yaml
  - rime/cn_dicts/base.dict.yaml
""",
            )
            write(pkg / "my.schema.yaml", "schema:\n  schema_id: my\n")
            write(pkg / "theme.yaml", "name: My Theme\nstyle: {}\n")
            write(pkg / "my.layout.yaml", "name: My\nstyle: {}\n")
            write(rime / "default.yaml", "config_version: 'test'\n")
            write(rime / "cn_dicts/base.dict.yaml", "name: base\n")

            self.assertEqual(
                package_schema.main([str(pkg)]),
                0,
            )

            with zipfile.ZipFile(pkg.with_suffix(".zip")) as z:
                self.assertEqual(
                    z.read("theme.yaml").decode("utf-8"),
                    "name: My Theme\nstyle: {}\n",
                )
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
            pkg = root / "my-schema"
            write(
                pkg / "manifest.yaml",
                """schema_id: my
name: My Schema
version: "1"
schema_file: my.schema.yaml
layout_files: [my.layout.yaml]
rime_files: [rime/default.yaml]
""",
            )
            write(pkg / "my.schema.yaml", "schema:\n  schema_id: my\n")
            write(pkg / "my.layout.yaml", "name: My\nstyle: {}\n")

            self.assertEqual(
                package_schema.main([str(pkg)]),
                1,
            )


if __name__ == "__main__":
    unittest.main()
