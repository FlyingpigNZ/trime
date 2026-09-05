#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package a self-contained Trime IME package directory into a zip.

Usage:
  python3 script/package_schema.py sample_theme_schemas/简纯+14键
  python3 script/package_schema.py sample_theme_schemas/简纯+14键 --rime-source sample_theme_schemas/rime.雾凇

The directory must contain a component `manifest.yaml` (with `components`).
Every local file in the package source is included, and `rime_files` listed in
the manifest are pulled from the package directory first or from
`--rime-source` / the sibling `rime.雾凇` directory when present. The output is
written next to the directory as `<name>.zip`.
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
    parser.add_argument("src", help="Self-contained IME package source directory")
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
    if "components" not in manifest:
        print("manifest.yaml must be a component manifest (missing 'components')", file=sys.stderr)
        return 1

    # Self-contained component packages include every local definition file.
    files = ["manifest.yaml"] + [
        name for name in local_package_files(src) if name != "manifest.yaml"
    ]
    # Rime files may come from the sibling rime.雾凇 source and are always added.
    rime_files = manifest.get("rime_files", [])
    if not isinstance(rime_files, list):
        print("manifest.yaml 'rime_files' must be a list", file=sys.stderr)
        return 1
    for name in rime_files:
        if name not in files:
            files.append(name)

    rime_source = Path(args.rime_source).resolve() if args.rime_source else None
    missing = [name for name in files if locate_file(src, name, rime_source) is None]
    if missing:
        print("Missing package files:", ", ".join(missing), file=sys.stderr)
        return 1

    schema_list_errors = validate_schema_list(files, src, rime_source)
    if schema_list_errors:
        print("Invalid schema_list references:", file=sys.stderr)
        for error in schema_list_errors:
            print("  -", error, file=sys.stderr)
        return 1

    out = src.with_suffix(".zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in files:
            z.write(locate_file(src, name, rime_source), arcname=name)
    print(f"Wrote {out}")
    return 0


def validate_schema_list(
    files: list[str],
    src: Path,
    rime_source: Path | None,
) -> list[str]:
    """Every `schema_list` entry in the packaged default.yaml must ship a
    `.schema.yaml` file: librime's workspace_update fails the whole deploy when
    a listed schema is missing, so such a package is unusable."""
    default_yaml = next(
        (name for name in files if name.endswith("default.yaml")),
        None,
    )
    if default_yaml is None:
        return []
    path = locate_file(src, default_yaml, rime_source)
    if path is None:
        return []
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        return [f"{default_yaml}: invalid YAML: {exc}"]
    if not isinstance(data, dict):
        return [f"{default_yaml}: must be a YAML mapping"]
    schema_list = data.get("schema_list") or []
    shipped = {
        Path(name).name.removesuffix(".schema.yaml")
        for name in files
        if name.endswith(".schema.yaml")
    }
    errors: list[str] = []
    for item in schema_list:
        if not isinstance(item, dict):
            continue
        schema_id = item.get("schema")
        if isinstance(schema_id, str) and schema_id not in shipped:
            errors.append(
                f"{default_yaml} lists schema '{schema_id}' which is not shipped "
                f"in the package (shipped: {', '.join(sorted(shipped)) or 'none'})",
            )
    return errors


if __name__ == "__main__":
    raise SystemExit(main())
