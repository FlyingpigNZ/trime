#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fail when a release APK ships an unstripped librime_jni.so.
#
# Why this exists: the JNI cache restores `app/prebuilt`, and while that
# directory exists the build packages it as `jniLibs` and configures *no* CMake
# build (see `NativeBaseConventionPlugin`). If the strip tool is unavailable in
# that run (AGP then logs "Unable to strip the following libraries, packaging
# them as they are"), whatever the cache holds lands in the APK verbatim: the
# libraries are 4-7x larger unstripped, which triples the APK (observed
# 8 MB -> 21 MB for `arm64-v8a`). The workflows now cache *stripped* libraries,
# and this check makes any recurrence loud instead of shipping it.
#
# Usage: script/check-native-libs-stripped.sh <apk> [<apk> ...]

set -euo pipefail

# arm64-v8a unstripped is ~93 MB (debug) / ~35 MB (release); stripped it is
# ~14 MB (debug-derived) and smaller for a release-compiled library. 24 MB
# separates the two regimes with room for a growing engine.
readonly MAX_LIB_BYTES=$((24 * 1024 * 1024))

status=0
for apk in "$@"; do
    [ -f "$apk" ] || {
        echo "missing APK: $apk" >&2
        exit 1
    }
    while read -r size name; do
        printf '%s: %s = %s bytes\n' "$(basename "$apk")" "$name" "$size"
        if [ "$size" -gt "$MAX_LIB_BYTES" ]; then
            printf '::error::%s ships an unstripped %s (%s bytes); the JNI cache must hold stripped libraries (see NativeBaseConventionPlugin).\n' \
                "$apk" "$name" "$size" >&2
            status=1
        fi
    done < <(unzip -l "$apk" | awk '$4 ~ /\.so$/ {print $1, $4}')
done
exit "$status"
