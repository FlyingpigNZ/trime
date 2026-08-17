#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the app-provided Default.zip IME package.

The default package is the self-contained tongwenfeng package plus the
built-in Rime schemas/resources under app/src/main/assets/shared.

Usage:
  python3 script/build_default_package.py
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

import package_schema

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "sample_theme_schemas" / "tongwenfeng"
RIME_SOURCE = ROOT / "app" / "src" / "main" / "assets" / "shared"
OUT = ROOT / "sample_theme_schemas" / "Default.zip"


def main() -> int:
    rc = package_schema.main([str(SRC), "--rime-source", str(RIME_SOURCE)])
    if rc:
        return rc
    generated = SRC.with_suffix(".zip")
    shutil.copyfile(generated, OUT)
    generated.unlink(missing_ok=True)
    print(f"Wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
