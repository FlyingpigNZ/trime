#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package a schema-layout directory into a customer zip.

Usage:
  python3 script/package_schema.py sample_theme_schemas/简纯+14键
  python3 script/package_schema.py sample_theme_schemas/简纯+14键 --rime-source sample_theme_schemas/rime.雾凇

The manifest itself plus its `schema_file`, `layout_files`, `resources`, and
`rime_files` are included in the zip. `rime_files` are looked up under the
package directory first; if absent, the `--rime-source` directory (or the
sibling `rime.雾凇` directory, when it exists) is used as the source. The output
is written next to the directory as `<name>.zip`.
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

import yaml

DEFAULT_RIME_SOURCE_NAME = "rime.雾凇"


def resolve_source(path: Path, rime_source: Path | None) -> Path | None:
    if rime_source is not None:
        return rime_source
    candidate = path.parent / DEFAULT_RIME_SOURCE_NAME
    return candidate if candidate.is_dir() else None


def locate_file(
    src: Path,
    name: str,
    rime_source: Path | None,
) -> Path | None:
    local = src / name
    if local.is_file():
        return local
    if name.startswith("rime/"):
        source = resolve_source(src, rime_source)
        if source is not None:
            candidate = source / name.removeprefix("rime/")
            if candidate.is_file():
                return candidate
    return None


def local_package_files(src: Path) -> list[str]:
    """All regular files inside a self-contained component package source."""
    return sorted(
        path.relative_to(src).as_posix()
        for path in src.rglob("*")
        if path.is_file() and path.suffix.lower() != ".zip"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("src", help="Schema-layout package source directory")
    parser.add_argument(
        "--rime-source",
        help="Directory containing the Rime shared-data files referenced by rime_files",
    )
    args = parser.parse_args(argv)

    src = Path(args.src).resolve()
    if not src.is_dir():
        print(f"Not a directory: {src}", file=sys.stderr)
        return 1

    manifest_path = src / "manifest.yaml"
    if not manifest_path.exists():
        print(f"Missing manifest.yaml in {src}", file=sys.stderr)
        return 1
    manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        print("manifest.yaml must contain a YAML mapping", file=sys.stderr)
        return 1
    schema_file = manifest.get("schema_file")
    theme_file = manifest.get("theme_file")
    layout_files = manifest.get("layout_files", [])
    resources = manifest.get("resources", [])
    rime_files = manifest.get("rime_files", [])

    has_component = (src / "component.yaml").is_file() or "components" in manifest
    if has_component:
        # Self-contained component packages include every local definition file.
        files = ["manifest.yaml"] + [
            name for name in local_package_files(src) if name != "manifest.yaml"
        ]
    else:
        files = ["manifest.yaml"]
        if schema_file:
            files.append(schema_file)
        if theme_file:
            files.append(theme_file)
        files.extend(layout_files)
        files.extend(resources)
    # Rime files may come from the sibling rime.雾凇 source and are always added.
    for name in rime_files:
        if name not in files:
            files.append(name)

    rime_source = Path(args.rime_source).resolve() if args.rime_source else None
    missing = [name for name in files if locate_file(src, name, rime_source) is None]
    if missing:
        print("Missing package files:", ", ".join(missing), file=sys.stderr)
        return 1

    out = src.with_suffix(".zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in files:
            z.write(locate_file(src, name, rime_source), arcname=name)
    print(f"Wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
