#!/usr/bin/env python3

# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compute the cache key for the prebuilt JNI libraries used by CI.

The key covers everything that changes the native output: the CMake and NDK
versions, the requested ABIs, the submodule commits, and the contents of our
own CMake and JNI sources.  When any of them changes, CI rebuilds the native
libraries instead of restoring ``app/prebuilt``.

This runs as a plain script instead of a Gradle task on purpose: asking Gradle
for the key configured the whole Android project (downloading the wrapper,
compiling build-logic and letting AGP install the NDK) and cost ~95 s per CI
job to print 64 characters.

⚠ The text that gets hashed must stay byte-identical to what the former Gradle
implementation (``NativeCacheHashPlugin``) produced, otherwise every existing
``app/prebuilt`` cache entry becomes unreachable and CI pays one full native
rebuild.  Two details matter:

* ``git submodule status`` runs from the repository root (so the paths it
  prints are root-relative) and its output is stripped of surrounding
  whitespace;
* files are visited in directory (``readdir``) order, not sorted, matching
  Gradle's ``fileTree`` iteration.  Sorting here would change every key.

If the key genuinely has to change, that is fine — just expect one cold CI run
that rebuilds the native libraries before the new cache entry exists.
"""

import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
VERSIONS_KT = ROOT / "build-logic/convention/src/main/kotlin/Versions.kt"

# Hashed sources: keep in sync with the native build in app/src/main/jni.
HASHED_DIRS = (
    APP / "src/main/jni/cmake",
    APP / "src/main/jni/librime_jni",
)
HASHED_FILES = (APP / "src/main/jni/CMakeLists.txt",)


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sha256_text(text):
    return hashlib.sha256(text.encode()).hexdigest()


def version_constant(name):
    """Read Versions.DEFAULT_* from the build-logic Kotlin file."""
    match = re.search(rf'\b{name}\s*=\s*"([^"]+)"', VERSIONS_KT.read_text())
    if match is None:
        raise SystemExit(f"cannot find {name} in {VERSIONS_KT}")
    return match.group(1)


def env_version(env_name, constant_name):
    """Mirror Project.envOrProp: environment wins over the code default."""
    value = os.environ.get(env_name, "").strip()
    return value or version_constant(constant_name)


def submodule_status():
    result = subprocess.run(
        ["git", "submodule", "status"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


def walk_files(directory):
    for dirpath, _, filenames in os.walk(directory):
        for name in filenames:
            yield Path(dirpath) / name


def cache_key():
    # appendLine() of a null override prints the literal "null".
    abi = os.environ.get("BUILD_ABI", "").strip()
    lines = [
        env_version("CMAKE_VERSION", "DEFAULT_CMAKE"),
        env_version("NDK_VERSION", "DEFAULT_NDK"),
        abi or "null",
        submodule_status(),
    ]
    for directory in HASHED_DIRS:
        for path in walk_files(directory):
            lines.append(sha256_file(path))
    for path in HASHED_FILES:
        lines.append(sha256_file(path))
    # Trailing newline after the last entry, like appendLine().
    return sha256_text("".join(line + "\n" for line in lines))


def main():
    key = cache_key()
    github_output = os.environ.get("GITHUB_OUTPUT", "").strip()
    if github_output:
        with open(github_output, "a", encoding="utf-8") as out:
            out.write(f"native-cache-hash={key}\n")
    print(key)
    return 0


if __name__ == "__main__":
    sys.exit(main())
