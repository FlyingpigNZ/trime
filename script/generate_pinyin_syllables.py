#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the pinyin syllable table for the T9 disambiguation feature.

The table lists every standard pinyin syllable (no tone) with the derived
codes used by the app-side disambiguation decoder
(`app/src/main/java/com/osfans/trime/ime/disambiguation/PinyinDisambiguationDecoder.kt`):

- t9_code:      full pinyin folded to T9 digits (A-Z -> 22233344455566677778889999)
- flypy_code:   小鹤双拼 key code (initial key + final key)
- flypy_t9_code: the 小鹤双拼 code folded to T9 digits
- flypy_14_code: the 小鹤双拼 code folded to the 14-key letters (mirroring the
  Rime `/14jian` preset); emitted only with `--flypy14` for the 小鹤双拼14键
  disambiguation table (`wanxiang_14jian.extended.yaml`)

The syllable list is extracted from the built-in luna_pinyin dictionary so it
matches the actual pinyin space of the bundled Rime data. 小鹤双拼 key mapping
is the standard one (initial table + final table).

Usage:
  python3 script/generate_pinyin_syllables.py [--flypy14] > syllables.yaml

The emitted YAML is meant to be pasted into the `t9_disambiguation.syllables`
section of `<schemaId>.extended.yaml` files.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 小鹤双拼 key mapping (方案特定数据; standard xiaohe layout).
XIAOHE_INITIALS: dict[str, str] = {
    "zh": "v", "ch": "i", "sh": "u",
    "b": "b", "p": "p", "m": "m", "f": "f", "d": "d", "t": "t", "n": "n", "l": "l",
    "g": "g", "k": "k", "h": "h", "j": "j", "q": "q", "x": "x",
    "r": "r", "z": "z", "c": "c", "s": "s", "y": "y", "w": "w",
}

# 小鹤双拼 final keys, mirrored from the wanxiang_algebra.yaml `/base/小鹤双拼`
# preset (the circled-letter -> key xlit there).
XIAOHE_FINALS: dict[str, str] = {
    "a": "a", "o": "o", "e": "e", "i": "i", "u": "u", "v": "v",
    "ai": "d", "ei": "w", "ui": "v", "ao": "c", "ou": "z", "iu": "q",
    "ie": "p", "ve": "t", "ue": "t", "er": "r",
    "an": "j", "en": "f", "in": "b", "un": "y", "vn": "y", "ang": "h",
    "eng": "g", "ing": "k", "ong": "s", "iong": "s",
    "ia": "x", "ua": "x", "uo": "o", "ian": "m", "uan": "r", "iang": "l",
    "uang": "l", "iao": "n", "uai": "k",
    # ü-vowel finals after l/n use the same keys as their u- counterparts.
    "van": "r", "ven": "f", "vin": "b", "vun": "y", "vang": "h",
}

# Standard 9-key fold: letters -> digits on a phone keypad.
LETTER_TO_T9 = {
    letter: str(digit)
    for digit, letters in {
        2: "abc", 3: "def", 4: "ghi", 5: "jkl",
        6: "mno", 7: "pqrs", 8: "tuv", 9: "wxyz",
    }.items()
    for letter in letters
}

# 14-key fold, mirrored from the Rime `/14jian` preset
# (wanxiang_algebra.yaml): QWERTYUIOPASDFGHJKLZXCVBNM ->
# qqeettuuooaaddggjjlzzccbbm. Each physical key sends one representative
# letter, so a 双拼 code folds lossily onto the 14 letters the keyboard sends.
LETTER_TO_14 = {
    "q": "q", "w": "q", "e": "e", "r": "e", "t": "t", "y": "t",
    "u": "u", "i": "u", "o": "o", "p": "o", "a": "a", "s": "a",
    "d": "d", "f": "d", "g": "g", "h": "g", "j": "j", "k": "j",
    "l": "l", "z": "z", "x": "z", "c": "c", "v": "c", "b": "b",
    "n": "b", "m": "m",
}


# Bare YAML words that PyYAML parses as booleans/null. A 双拼 code like `no`
# (nuo) must be quoted, or the validator would see False instead of the code.
YAML_RISKY_CODES = {"no", "on", "off", "yes", "true", "false", "null", "y", "n"}


def quote_if_yaml_risky(code: str) -> str:
    """Single-quote a code when PyYAML would otherwise parse it as a scalar."""
    return "'%s'" % code if code.lower() in YAML_RISKY_CODES else code


def t9_code(s: str) -> str:
    return "".join(LETTER_TO_T9[ch] for ch in s.lower())


def flypy14_code(code: str) -> str:
    """小鹤双拼 key code -> 14-key letter fold (mirrors `/14jian`)."""
    return "".join(LETTER_TO_14[ch] for ch in code.lower())


def flypy_code(pinyin: str) -> str:
    """Full pinyin -> 小鹤双拼 key code (initial + final)."""
    for init_len in (2, 1):
        initial = pinyin[:init_len]
        final = pinyin[init_len:]
        if initial in XIAOHE_INITIALS and final in XIAOHE_FINALS:
            return XIAOHE_INITIALS[initial] + XIAOHE_FINALS[final]
    # Zero-initial syllables (a, ai, ao, ...): mirror the Rime /base/小鹤双拼
    # algebra, where every syllable is exactly two keys but the guide+key form
    # is only used when the natural spelling is not already two keys:
    #   - two-letter finals keep their natural spelling (ai -> ai, an -> an,
    #     ao -> ao, ei -> ei, en -> en, ou -> ou — the algebra drops the
    #     guide+key forms `^(aj|ac|ad|ew|ef|oz)(\d?)$`);
    #   - single-letter finals double (a -> aa, o -> oo, e -> ee);
    #   - longer finals take a vowel guide + the final key (ang -> ah, eng -> eg).
    if pinyin in XIAOHE_FINALS:
        return pinyin if len(pinyin) == 2 else pinyin[0] + XIAOHE_FINALS[pinyin]
    raise ValueError(f"cannot encode '{pinyin}' to 小鹤双拼")


# Non-standard interjections present in the dictionary but not valid pinyin
# syllables for the disambiguation table (they have no 小鹤双拼 encoding).
NON_STANDARD = {"eh"}


def extract_syllables() -> set[str]:
    """Unique plain pinyin syllables from the built-in luna_pinyin dict."""
    syllables: set[str] = set()
    dict_file = ROOT / "app/src/main/assets/shared/Default/rime/luna_pinyin.dict.yaml"
    for line in dict_file.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or line.startswith("---") or line.startswith("..."):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        py = parts[1].strip()
        if re.fullmatch(r"[a-zü]+", py):
            syllables.add(py.replace("ü", "v"))
    return syllables


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    add_flypy14 = "--flypy14" in argv
    syllables = sorted(extract_syllables() - NON_STANDARD)
    print("# Auto-generated by script/generate_pinyin_syllables.py; do not edit by hand.")
    print("# %d syllables, no tone." % len(syllables))
    for py in syllables:
        fp = flypy_code(py)
        if add_flypy14:
            # Quote the 双拼 code: bare YAML words such as `no` (nuo's code)
            # would otherwise parse as booleans under PyYAML.
            print(
                "    - {pinyin: %s, t9_code: %s, flypy_code: '%s', flypy_t9_code: %s, flypy_14_code: '%s'}"
                % (py, t9_code(py), fp, t9_code(fp), flypy14_code(fp))
            )
        else:
            # Quote only the risky words here so existing T9 tables regenerate
            # byte-identically except for those rows.
            print(
                "    - {pinyin: %s, t9_code: %s, flypy_code: %s, flypy_t9_code: %s}"
                % (py, t9_code(py), quote_if_yaml_risky(fp), t9_code(fp))
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
