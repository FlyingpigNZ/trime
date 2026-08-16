#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the shared-aux component from tongwenfeng (Option A).

Reads:
  app/src/main/assets/shared/tongwenfeng.trime.yaml   (base)
  app/src/main/assets/shared/standard/*.yaml          (to drop exact duplicates)

Writes:
  sample_theme_schemas/shared-aux/
    component.yaml   — component metadata
    keyboard.yaml    — preset_keyboards
    behavior.yaml    — preset_keys
    style.yaml       — style / liquid_keyboard
    color.yaml       — preset_color_schemes / fallback_colors

Option A: shared-aux is the clean tongwenfeng base. Entries that are exactly
identical to the standard catalog are not copied into shared-aux (the standard
component already provides them). The existing monolithic theme files are left
untouched so the runtime keeps working during the transition.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/assets/shared/tongwenfeng.trime.yaml"
STANDARD_DIR = ROOT / "app/src/main/assets/shared/standard"
OUT_DIR = ROOT / "sample_theme_schemas/shared-aux"


def load(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def dump(obj: object) -> str:
    return yaml.safe_dump(obj, allow_unicode=True, sort_keys=False, width=1000)


def drop_identical_to_standard(section: str, values: dict, standard_file: str) -> dict:
    standard = load(STANDARD_DIR / standard_file).get(section, {})
    return {k: v for k, v in values.items() if k not in standard or standard[k] != v}


def main() -> int:
    if not SOURCE.exists():
        print(f"Missing source: {SOURCE}", file=sys.stderr)
        return 1
    data = load(SOURCE)

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    keyboards = data.get("preset_keyboards", {})
    preset_keys = drop_identical_to_standard(
        "preset_keys", data.get("preset_keys", {}), "preset_keys.yaml"
    )
    color_schemes = drop_identical_to_standard(
        "preset_color_schemes", data.get("preset_color_schemes", {}), "colors.yaml"
    )

    (OUT_DIR / "component.yaml").write_text(
        "# shared-aux component\n"
        "# Base: tongwenfeng.trime.yaml (Option A)\n"
        + dump(
            {
                "name": "shared-aux",
                "description": "Shared helper keyboards, behaviors, styles, and colors derived from tongwenfeng.",
                "source": "app/src/main/assets/shared/tongwenfeng.trime.yaml",
            }
        ),
        encoding="utf-8",
    )

    (OUT_DIR / "keyboard.yaml").write_text(
        "# Shared auxiliary keyboards (tongwenfeng base)\n"
        + dump({"preset_keyboards": keyboards}),
        encoding="utf-8",
    )

    (OUT_DIR / "behavior.yaml").write_text(
        "# Shared preset key behaviors/macros (tongwenfeng base, standard duplicates dropped)\n"
        + dump({"preset_keys": preset_keys}),
        encoding="utf-8",
    )

    # Style/color/liquid are intentionally theme-specific and are NOT part of
    # shared-aux; each theme provides its own style.yaml / color.yaml.

    print(f"Wrote shared-aux component to {OUT_DIR}")
    for path in sorted(OUT_DIR.glob("*.yaml")):
        print(f"  {path.name}: {path.stat().st_size} bytes")
    print(f"preset_keys after dropping standard duplicates: {len(preset_keys)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
