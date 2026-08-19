#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="com.osfans.trime.debug"
BASE="/storage/emulated/0/Android/data/$PKG/files"

if ! "$ADB" devices | grep -q "device$"; then
  echo "No adb device connected" >&2
  exit 1
fi

echo "== Test A: old /rime without active manifest -> Migrated + Default active =="
"$ADB" shell pm clear "$PKG" >/dev/null
"$ADB" shell mkdir -p "$BASE/rime"
"$ADB" shell "echo 'config_version: \"0.40\"' > $BASE/rime/default.yaml"
"$ADB" shell "echo 'user: data' > $BASE/rime/user.yaml"
"$ADB" shell "echo 'legacy.schema.yaml' > $BASE/rime/legacy.schema.yaml"
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/com.osfans.trime.ui.main.MainActivity" >/dev/null
sleep 8
"$ADB" shell test -f "$BASE/packages/Migrated/workspace/default.yaml"
ACTIVE_A=$("$ADB" shell run-as "$PKG" cat files/active-package | tr -d '\r')
if [ "$ACTIVE_A" != "Default" ]; then
  echo "FAIL: active should be Default, got '$ACTIVE_A'" >&2
  exit 1
fi
echo "PASS: Migrated workspace created and Default active"

echo "== Test B: old /rime with active-manifest -> package workspace + that package active =="
"$ADB" shell pm clear "$PKG" >/dev/null
"$ADB" shell mkdir -p "$BASE/rime/IMEs/14jian"
"$ADB" shell "echo 'config_version: \"0.40\"' > $BASE/rime/default.yaml"
"$ADB" shell "echo 'user: data' > $BASE/rime/user.yaml"
"$ADB" shell "echo 'patch: {}' > $BASE/rime/default.custom.yaml"
"$ADB" shell "echo '14jian.schema.yaml' > $BASE/rime/14jian.schema.yaml"
"$ADB" shell "cat > $BASE/rime/IMEs/active-manifest.yaml <<'YAML'
active_package: 14jian.zip
package_id: 14jian
name: 14jian
schema_id: 14jian
default_keyboard: 14jian
rime_files:
- default.yaml
- user.yaml
- 14jian.schema.yaml
YAML"
"$ADB" shell "cat > $BASE/rime/IMEs/14jian/manifest.yaml <<'YAML'
name: 14jian
schema_id: 14jian
default_keyboard: 14jian
components:
- style:
    file: style.yaml
- color:
    file: color.yaml
YAML"
"$ADB" shell "cat > $BASE/rime/IMEs/14jian/style.yaml <<'YAML'
style:
  keyboard_height: 240
  horizontal: true
  color_scheme: default
YAML"
"$ADB" shell "cat > $BASE/rime/IMEs/14jian/color.yaml <<'YAML'
preset_color_schemes:
  default:
    name: 14jian
    back_color: '0xff222222'
    text_color: '0xffe6e3d8'
    candidate_text_color: '0xffe6e3d8'
YAML"
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/com.osfans.trime.ui.main.MainActivity" >/dev/null
sleep 10
# Trigger the IME so ensureDefaultPackageReady runs the full startup path;
# the migrated package must stay active instead of being rolled back.
"$ADB" shell input keyevent KEYCODE_HOME >/dev/null
"$ADB" shell am start -a android.intent.action.SENDTO -d sms:12345 >/dev/null
sleep 2
"$ADB" shell ime enable "$PKG/com.osfans.trime.ime.core.TrimeInputMethodService" >/dev/null
"$ADB" shell ime set "$PKG/com.osfans.trime.ime.core.TrimeInputMethodService" >/dev/null
"$ADB" shell am start -a android.intent.action.SENDTO -d sms:12345 >/dev/null
sleep 2
"$ADB" shell ime set "$PKG/com.osfans.trime.ime.core.TrimeInputMethodService" >/dev/null
sleep 5
"$ADB" shell test -f "$BASE/packages/14jian/workspace/manifest.yaml"
ACTIVE_B=$("$ADB" shell run-as "$PKG" cat files/active-package | tr -d '\r')
if [ "$ACTIVE_B" != "14jian" ]; then
  echo "FAIL: active should be 14jian, got '$ACTIVE_B'" >&2
  exit 1
fi
echo "PASS: 14jian workspace migrated and active"

echo "== Test C: old /rime with active manifest but unusable theme -> fallback to Default =="
"$ADB" shell pm clear "$PKG" >/dev/null
"$ADB" shell mkdir -p "$BASE/rime/IMEs/14jian"
"$ADB" shell "echo 'config_version: \"0.40\"' > $BASE/rime/default.yaml"
"$ADB" shell "echo 'user: data' > $BASE/rime/user.yaml"
"$ADB" shell "echo 'patch: {}' > $BASE/rime/default.custom.yaml"
"$ADB" shell "echo '14jian.schema.yaml' > $BASE/rime/14jian.schema.yaml"
"$ADB" shell "cat > $BASE/rime/IMEs/active-manifest.yaml <<'YAML'
active_package: 14jian.zip
package_id: 14jian
name: 14jian
schema_id: 14jian
default_keyboard: 14jian
rime_files:
- default.yaml
- user.yaml
- 14jian.schema.yaml
YAML"
"$ADB" shell "cat > $BASE/rime/IMEs/14jian/manifest.yaml <<'YAML'
name: 14jian
schema_id: 14jian
default_keyboard: 14jian
components:
- style:
    file: style.yaml
- color:
    file: color.yaml
YAML"
"$ADB" shell "echo 'style: {}' > $BASE/rime/IMEs/14jian/style.yaml"
"$ADB" shell "echo 'preset_color_schemes: {}' > $BASE/rime/IMEs/14jian/color.yaml"
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/com.osfans.trime.ui.main.MainActivity" >/dev/null
sleep 10
"$ADB" shell input keyevent KEYCODE_HOME >/dev/null
"$ADB" shell am start -a android.intent.action.SENDTO -d sms:12345 >/dev/null
sleep 2
"$ADB" shell ime enable "$PKG/com.osfans.trime.ime.core.TrimeInputMethodService" >/dev/null
"$ADB" shell ime set "$PKG/com.osfans.trime.ime.core.TrimeInputMethodService" >/dev/null
"$ADB" shell am start -a android.intent.action.SENDTO -d sms:12345 >/dev/null
sleep 2
"$ADB" shell ime set "$PKG/com.osfans.trime.ime.core.TrimeInputMethodService" >/dev/null
sleep 5
ACTIVE_C=$("$ADB" shell run-as "$PKG" cat files/active-package | tr -d '\r')
if [ "$ACTIVE_C" != "Default" ]; then
  echo "FAIL: active should fall back to Default for unusable theme, got '$ACTIVE_C'" >&2
  exit 1
fi
if "$ADB" shell test -f "$BASE/packages/14jian/workspace/compiled.marker"; then
  echo "FAIL: unusable-theme migrated package must not be marked compiled" >&2
  exit 1
fi
echo "PASS: unusable migrated theme falls back to Default and is not marked compiled"

echo "All migration tests passed"
