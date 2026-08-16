#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate Trime definition YAML files against the three-tier schema.

Usage:
  python3 script/validate-definitions.py <file> [--kind manifest|theme|layout]
  python3 script/validate-definitions.py --check-shipped
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
STANDARD_DIR = ROOT / "app/src/main/assets/shared/standard"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from component_resolver import ComponentError, ComponentResolver  # noqa: E402


def load_standard() -> tuple[dict, dict]:
    keyboards = yaml.safe_load((STANDARD_DIR / "keyboards.yaml").read_text(encoding="utf-8"))["preset_keyboards"]
    colors = yaml.safe_load((STANDARD_DIR / "colors.yaml").read_text(encoding="utf-8"))["preset_color_schemes"]
    return keyboards, colors


def validate_manifest(data: dict) -> list[str]:
    errors: list[str] = []
    for field in ("schema_id", "name", "version", "schema_file", "layout_files"):
        if field not in data:
            errors.append(f"Missing required field '{field}'")
    if isinstance(data.get("layout_files"), list) and not data["layout_files"]:
        errors.append("Field 'layout_files' must not be empty")
    theme_file = data.get("theme_file")
    if theme_file is not None and not isinstance(theme_file, str):
        errors.append("Field 'theme_file' must be a string")
    rime_files = data.get("rime_files")
    if rime_files is not None:
        if not isinstance(rime_files, list):
            errors.append("Field 'rime_files' must be a list")
        else:
            for name in rime_files:
                if not isinstance(name, str):
                    errors.append("Field 'rime_files' entries must be strings")
                elif not name.startswith("rime/"):
                    errors.append(f"Rime file '{name}' must be under 'rime/'")
    return errors


def validate_theme(data: dict, keyboards: dict, colors: dict) -> list[str]:
    errors: list[str] = []
    if not data.get("name"):
        errors.append("Missing required field 'name'")
    if "style" not in data:
        errors.append("Missing required field 'style'")
    for name in data.get("standard_keyboards", []):
        if name not in keyboards:
            errors.append(f"Unknown standard keyboard '{name}'")
    for name in data.get("standard_color_schemes", []):
        if name not in colors:
            errors.append(f"Unknown standard color scheme '{name}'")
    return errors


def validate_layout(data: dict, keyboards: dict, colors: dict) -> list[str]:
    return validate_theme({**data, "name": data.get("name") or "fragment", "style": data.get("style") or {}}, keyboards, colors)


def validate_component_manifest(data: dict, path: Path) -> list[str]:
    try:
        sections = ComponentResolver(path.parent).resolve_manifest(data)
    except ComponentError as exc:
        return [str(exc)]

    from behavior_verifier import verify as verify_behavior
    from color_verifier import verify as verify_colors

    errors: list[str] = []
    errors += verify_behavior(sections)
    errors += verify_colors(sections)
    return errors


def validate_file(path: Path, kind: str | None) -> list[str]:
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        return [f"Invalid YAML: {exc}"]
    if not isinstance(data, dict):
        return ["Definition must be a YAML mapping"]
    if "components" in data:
        return validate_component_manifest(data, path)
    keyboards, colors = load_standard()
    if kind == "manifest" or (kind is None and {"schema_id", "schema_file", "layout_files"} & data.keys()):
        return validate_manifest(data)
    if kind == "layout" or (kind is None and "style" not in data and "preset_keyboards" in data):
        return validate_layout(data, keyboards, colors)
    return validate_theme(data, keyboards, colors)


def check_shipped() -> int:
    errors: list[str] = []
    reference_dirs = [
        ROOT / "sample_theme_schemas/minimal-14jian",
        ROOT / "sample_theme_schemas/简纯+14键",
    ]
    for ref_dir in reference_dirs:
        for path in sorted(ref_dir.glob("*.yaml")):
            if path.name == "manifest.yaml":
                kind = "manifest"
            elif path.name == "theme.yaml":
                kind = "theme"
            elif "layout" in path.name:
                kind = "layout"
            else:
                continue
            found = validate_file(path, kind)
            if found:
                errors.append(f"{path.relative_to(ROOT)}:\n  " + "\n  ".join(found))
    component_manifests = [
        ROOT / "sample_theme_schemas/tongwenfeng/manifest.yaml",
    ]
    for path in component_manifests:
        found = validate_file(path, "manifest")
        if found:
            errors.append(f"{path.relative_to(ROOT)}:\n  " + "\n  ".join(found))
    standard_checks = {
        STANDARD_DIR / "colors.yaml": "preset_color_schemes",
        STANDARD_DIR / "keyboards.yaml": "preset_keyboards",
        STANDARD_DIR / "preset_keys.yaml": "preset_keys",
    }
    for path, expected_key in standard_checks.items():
        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{path.relative_to(ROOT)}: Invalid YAML: {exc}")
            continue
        if not isinstance(data, dict) or expected_key not in data:
            errors.append(f"{path.relative_to(ROOT)}: Missing expected top-level key '{expected_key}'")
    for path in [
        ROOT / "app/src/main/assets/shared/trime.yaml",
        ROOT / "sample_theme_schemas/tongwenfeng.trime.yaml",
    ]:
        found = validate_file(path, "theme")
        if found:
            errors.append(f"{path.relative_to(ROOT)}:\n  " + "\n  ".join(found))
    if errors:
        print("\n".join(errors))
        return 1
    print("All shipped definitions are valid.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", nargs="?", help="YAML file to validate")
    parser.add_argument("--kind", choices=["manifest", "theme", "layout"])
    parser.add_argument("--check-shipped", action="store_true", help="Validate shipped standard/reference files")
    args = parser.parse_args()

    if args.check_shipped:
        return check_shipped()
    if not args.file:
        parser.error("a file argument or --check-shipped is required")
    errors = validate_file(Path(args.file), args.kind)
    if errors:
        print("\n".join(errors))
        return 1
    print("Definition is valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
