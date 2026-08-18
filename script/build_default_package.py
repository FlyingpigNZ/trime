#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the app-provided Default.zip IME package.

The package source lives at app/src/main/assets/shared/Default. Packaging that
folder produces app/src/main/assets/shared/Default.zip, which is what the APK
ships and DataManager installs into /rime/IMEs.

Usage:
  python3 script/build_default_package.py
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

import package_schema

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main" / "assets" / "shared" / "Default"
OUT = ROOT / "app" / "src" / "main" / "assets" / "shared" / "Default.zip"
SAMPLE_OUT = ROOT / "sample_theme_schemas" / "Default.zip"


def main() -> int:
    if not SRC.is_dir():
        print(f"Missing Default package source: {SRC}", file=sys.stderr)
        return 1
    rc = package_schema.main([str(SRC)])
    if rc:
        return rc
    # package_schema writes <src>.zip, i.e. OUT.
    if not OUT.is_file():
        print(f"Expected package output missing: {OUT}", file=sys.stderr)
        return 1
    shutil.copyfile(OUT, SAMPLE_OUT)
    print(f"Wrote {OUT}")
    print(f"Copied sample to {SAMPLE_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
