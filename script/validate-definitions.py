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
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent

sys.path.insert(0, str(Path(__file__).resolve().parent))
from component_resolver import ComponentError, ComponentResolver  # noqa: E402


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


def check_shipped() -> int:
    errors: list[str] = []
    component_manifests = sorted(
        list((ROOT / "sample_theme_schemas").glob("*/manifest.yaml"))
        + [ROOT / "app/src/main/assets/shared/Default/manifest.yaml"]
    )
    for path in component_manifests:
        found = validate_file(path)
        if found:
            errors.append(f"{path.relative_to(ROOT)}:\n  " + "\n  ".join(found))
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
