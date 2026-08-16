#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package a schema-layout directory into a customer zip.

Usage:
  python3 script/package_schema.py sample_theme_schemas/简纯+14键

The manifest's `schema_file`, `layout_files`, and `resources` are included in
the zip. The output is written next to the directory as `<name>.zip`.
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

import yaml


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args:
        print(__doc__, file=sys.stderr)
        return 2
    src = Path(args[0]).resolve()
    if not src.is_dir():
        print(f"Not a directory: {src}", file=sys.stderr)
        return 1

    manifest_path = src / "manifest.yaml"
    if not manifest_path.exists():
        print(f"Missing manifest.yaml in {src}", file=sys.stderr)
        return 1
    manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
    schema_file = manifest.get("schema_file")
    layout_files = manifest.get("layout_files", [])
    resources = manifest.get("resources", [])

    files = []
    if schema_file:
        files.append(schema_file)
    files.extend(layout_files)
    files.extend(resources)

    missing = [name for name in files if not (src / name).exists()]
    if missing:
        print("Missing package files:", ", ".join(missing), file=sys.stderr)
        return 1

    out = src.with_suffix(".zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in files:
            z.write(src / name, arcname=name)
    print(f"Wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
