#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerate manifest.yaml for a self-contained Trime IME package source.

Scans the package directory and rewrites the `components` and `rime_files`
sections of `manifest.yaml`, keeping the metadata fields (`name`, `author`,
`version`, `schema_id`, `default_keyboard`) from the previous manifest when
present (or deriving them for a brand-new package). Run this after adding or
renaming definition/Rime files, then package with `package_schema.py`:

  python3 script/generate_manifest.py sample_theme_schemas/简纯+14键
  python3 script/package_schema.py sample_theme_schemas/简纯+14键

Only the two generated sections are rewritten; this is a full YAML rewrite,
so hand-written comments in the old manifest are not preserved.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

# Root-level definition files and the component type they map to.
COMPONENT_TYPE_BY_FILE = {
    "keyboard.yaml": "keyboard",
    "behavior.yaml": "behavior",
    "color.yaml": "color",
    "style.yaml": "style",
    "liquid_keyboard.yaml": "style",
    "chrome.yaml": "style",
}

SCHEMA_SUFFIX = ".schema.yaml"

METADATA_KEYS = ("name", "author", "version", "schema_id", "default_keyboard")


def root_definition_files(src: Path) -> list[Path]:
    """Root-level YAML definition files, in a stable order."""
    return sorted(p for p in src.iterdir() if p.is_file() and p.suffix.lower() == ".yaml")


def build_components(src: Path) -> list[dict[str, dict[str, str]]]:
    components: list[dict[str, dict[str, str]]] = []
    for path in root_definition_files(src):
        if path.name == "manifest.yaml":
            continue
        if path.name.endswith(SCHEMA_SUFFIX):
            components.append({"schema": {"file": path.name}})
            continue
        kind = COMPONENT_TYPE_BY_FILE.get(path.name)
        if kind is None:
            print(
                f"warning: {path.name}: unknown root definition file, "
                "not added to components",
                file=sys.stderr,
            )
            continue
        components.append({kind: {"file": path.name}})
    return components


def build_rime_files(src: Path) -> list[str]:
    rime_dir = src / "rime"
    if not rime_dir.is_dir():
        return []
    return sorted(
        path.relative_to(src).as_posix()
        for path in rime_dir.rglob("*")
        if path.is_file()
    )


def derive_metadata(src: Path) -> dict[str, str]:
    """Metadata for a package without an existing manifest."""
    schema_files = sorted(p.name for p in root_definition_files(src) if p.name.endswith(SCHEMA_SUFFIX))
    schema_id = schema_files[0].removesuffix(SCHEMA_SUFFIX) if schema_files else src.name
    return {
        "name": src.name,
        "schema_id": schema_id,
        "default_keyboard": schema_id,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("src", help="Self-contained IME package source directory")
    args = parser.parse_args(argv)

    src = Path(args.src).resolve()
    if not src.is_dir():
        print(f"Not a directory: {src}", file=sys.stderr)
        return 1

    manifest_path = src / "manifest.yaml"
    metadata: dict[str, str] = {}
    if manifest_path.exists():
        loaded = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
        if isinstance(loaded, dict):
            metadata = {
                key: loaded[key] for key in METADATA_KEYS if key in loaded and loaded[key] is not None
            }
    if not metadata:
        metadata = derive_metadata(src)

    manifest = {
        **metadata,
        "components": build_components(src),
        "rime_files": build_rime_files(src),
    }
    manifest_path.write_text(
        yaml.safe_dump(manifest, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    print(f"Wrote {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
