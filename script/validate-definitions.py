#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate self-contained Trime IME package component manifests.

Usage:
  python3 script/validate-definitions.py <manifest.yaml>
  python3 script/validate-definitions.py --check-shipped
"""

from __future__ import annotations

import argparse
import sys
import tempfile
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent

sys.path.insert(0, str(Path(__file__).resolve().parent))
from component_resolver import ComponentError, ComponentResolver  # noqa: E402
from extended_validator import EXTENDED_SUFFIX, validate_extended_file  # noqa: E402


def validate_component_manifest(data: dict, path: Path) -> list[str]:
    try:
        sections = ComponentResolver(path.parent).resolve_manifest(data)
    except ComponentError as exc:
        return [str(exc)]

    from behavior_verifier import verify as verify_behavior
    from color_verifier import verify as verify_colors

    errors: list[str] = []
    if not sections.get("style"):
        errors.append("Component package must define a non-empty 'style' section")
    if not sections.get("preset_color_schemes"):
        errors.append("Component package must define at least one 'preset_color_schemes' entry")
    errors += verify_behavior(sections)
    errors += verify_colors(sections)
    return errors


def validate_file(path: Path) -> list[str]:
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        return [f"Invalid YAML: {exc}"]
    if not isinstance(data, dict):
        return ["Definition must be a YAML mapping"]
    if "components" not in data:
        return ["Not a component manifest: missing 'components'"]
    return validate_component_manifest(data, path)


def validate_zip(zip_path: Path) -> list[str]:
    """Validate a packaged component zip by extracting it to a temp dir."""
    errors: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        extract_dir = Path(tmp)
        with zipfile.ZipFile(zip_path) as archive:
            archive.extractall(extract_dir)
        manifest = extract_dir / "manifest.yaml"
        if not manifest.is_file():
            manifest = extract_dir / "component.yaml"
        if not manifest.is_file():
            return ["package has no manifest.yaml/component.yaml"]
        errors += validate_file(manifest)
        errors += _validate_extended_files_in(extract_dir)
    return errors


def _validate_extended_files_in(package_dir: Path) -> list[str]:
    """Validate every `<schemaId>.extended.yaml` present in a package dir."""
    errors: list[str] = []
    for path in sorted(package_dir.rglob(f"*{EXTENDED_SUFFIX}")):
        found = validate_extended_file(path)
        if found:
            errors.append(f"{path.relative_to(package_dir)}:\n  " + "\n  ".join(found))
    return errors


def check_shipped() -> int:
    errors: list[str] = []
    # Source-form manifests: the Default package and any unpacked samples.
    component_manifests = sorted(
        list((ROOT / "sample_theme_schemas").glob("*/manifest.yaml"))
        + [ROOT / "app/src/main/assets/shared/Default/manifest.yaml"]
    )
    for path in component_manifests:
        found = validate_file(path)
        if found:
            errors.append(f"{path.relative_to(ROOT)}:\n  " + "\n  ".join(found))
        # Source-form extended files next to the manifest.
        found = _validate_extended_files_in(path.parent)
        if found:
            errors.append(f"{path.parent.relative_to(ROOT)}:\n  " + "\n  ".join(found))
    # Packaged samples: validate the actual zips shipped in the repo, not
    # just their unpacked source, so a stale or corrupt zip is caught.
    sample_zips = sorted((ROOT / "sample_theme_schemas").glob("*.zip"))
    if not sample_zips:
        errors.append("sample_theme_schemas: no *.zip found to validate (renamed/deleted?)")
    for zip_path in sample_zips:
        found = validate_zip(zip_path)
        if found:
            errors.append(f"{zip_path.relative_to(ROOT)}:\n  " + "\n  ".join(found))
    if errors:
        print("\n".join(errors))
        return 1
    print("All shipped definitions are valid.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", nargs="?", help="Component manifest YAML to validate")
    parser.add_argument("--check-shipped", action="store_true", help="Validate shipped component manifests")
    args = parser.parse_args()

    if args.check_shipped:
        return check_shipped()
    if not args.file:
        parser.error("a file argument or --check-shipped is required")
    errors = validate_file(Path(args.file))
    if errors:
        print("\n".join(errors))
        return 1
    print("Definition is valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
