#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate `<schemaId>.extended.yaml` per-schema extension files.

Mirrors the Kotlin parser in
`app/src/main/java/com/osfans/trime/data/theme/SchemaExtension.kt`:
- the file must be a YAML mapping
- it must declare at least one of `schema_id` / `tool_bar` /
  `t9_disambiguation`
- `tool_bar.__replace` must be a boolean
- `t9_disambiguation.input_method` must be `full`, `flypy` or `flypy14`
- every `syllables` entry must carry `pinyin` + `t9_code`, and the codes must
  be consistent (a syllable's `t9_code` must equal the T9 fold of its pinyin;
  the flypy codes must match the 双拼 key table when provided; a present
  `flypy_14_code` must equal the `/14jian` fold of the `flypy_code` and a
  present `flypy_14_token` must equal the `/14jian-token` fold; a `flypy14`
  table must carry `flypy_14_code` and `flypy_14_token` on every entry)

The pinyin/T9 fold logic is shared with the generator
(`generate_pinyin_syllables.py`) so the shipped data and the validator can
never disagree.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from generate_pinyin_syllables import (
    flypy14_code,
    flypy14_token_code,
    flypy_code,
    t9_code,
)

EXTENDED_SUFFIX = ".extended.yaml"


def validate_extended_file(path: Path) -> list[str]:
    errors: list[str] = []
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        return [f"{path}: invalid YAML: {exc}"]
    if not isinstance(data, dict):
        return [f"{path}: extended file must be a YAML mapping"]
    prefix = f"{path.name}:"
    has_any = False

    schema_id = data.get("schema_id")
    if schema_id is not None:
        has_any = True
        if not isinstance(schema_id, str) or not schema_id:
            errors.append(f"{prefix} schema_id must be a non-empty string")
        else:
            # The schema_id declared inside the file must match its file name:
            # `<schemaId>.extended.yaml` is loaded by translating the file name
            # to a schema id, so a mismatch would silently bind the wrong id.
            from_name = path.name.removesuffix(EXTENDED_SUFFIX)
            if schema_id != from_name:
                errors.append(
                    f"{prefix} schema_id '{schema_id}' does not match file name "
                    f"'{from_name}'"
                )

    if "tool_bar" in data:
        has_any = True
        tool_bar = data["tool_bar"]
        if not isinstance(tool_bar, dict):
            errors.append(f"{prefix} tool_bar must be a mapping")
        else:
            replace = tool_bar.get("__replace")
            if replace is not None and not isinstance(replace, bool):
                errors.append(
                    f"{prefix} tool_bar.__replace must be a boolean, got {replace!r}"
                )

    if "t9_disambiguation" in data:
        has_any = True
        t9 = data["t9_disambiguation"]
        errors += _validate_t9(t9, prefix)

    if not has_any:
        errors.append(
            f"{prefix} extended file must declare at least one of "
            "'schema_id', 'tool_bar', 't9_disambiguation'"
        )
    return errors


def _validate_t9(t9: Any, prefix: str) -> list[str]:
    errors: list[str] = []
    if not isinstance(t9, dict):
        errors.append(f"{prefix} t9_disambiguation must be a mapping")
        return errors
    enabled = t9.get("enabled")
    if enabled is not None and not isinstance(enabled, bool):
        errors.append(f"{prefix} t9_disambiguation.enabled must be a boolean")
    input_method = t9.get("input_method")
    if input_method is not None and input_method not in ("full", "flypy", "flypy14"):
        errors.append(
            f"{prefix} t9_disambiguation.input_method must be 'full', 'flypy' or "
            f"'flypy14', got {input_method!r}"
        )
    syllables = t9.get("syllables")
    if syllables is not None:
        if not isinstance(syllables, list):
            errors.append(f"{prefix} t9_disambiguation.syllables must be a list")
        else:
            if input_method == "flypy14":
                missing = [
                    entry.get("pinyin")
                    for entry in syllables
                    if isinstance(entry, dict)
                    and (
                        not entry.get("flypy_code")
                        or not entry.get("flypy_14_code")
                        or not entry.get("flypy_14_token")
                    )
                ]
                if missing:
                    errors.append(
                        f"{prefix} t9_disambiguation.input_method 'flypy14' requires "
                        f"'flypy_code', 'flypy_14_code' and 'flypy_14_token' on every "
                        f"syllable; missing for {missing!r}"
                    )
            for i, entry in enumerate(syllables):
                errors += _validate_syllable(entry, i, prefix)
    flypy_keys = t9.get("flypy_keys")
    if flypy_keys is not None:
        if not isinstance(flypy_keys, dict):
            errors.append(f"{prefix} t9_disambiguation.flypy_keys must be a mapping")
    return errors


def _validate_syllable(entry: Any, index: int, prefix: str) -> list[str]:
    errors: list[str] = []
    if not isinstance(entry, dict):
        errors.append(f"{prefix} t9_disambiguation.syllables[{index}] must be a mapping")
        return errors
    pinyin = entry.get("pinyin")
    t9 = entry.get("t9_code")
    if not isinstance(pinyin, str) or not pinyin:
        errors.append(f"{prefix} t9_disambiguation.syllables[{index}] missing 'pinyin'")
        return errors
    # The app-side YAML parser reads every scalar as a string; PyYAML parses
    # unquoted digit runs as ints. Accept both — they represent the same code.
    if isinstance(t9, bool) or not isinstance(t9, (str, int)):
        errors.append(f"{prefix} t9_disambiguation.syllables[{index}] missing 't9_code'")
        return errors
    t9 = str(t9)
    # The derived code must equal the T9 fold of the pinyin.
    expected = t9_code(pinyin)
    if t9 != expected:
        errors.append(
            f"{prefix} t9_disambiguation.syllables[{index}] pinyin '{pinyin}' "
            f"t9_code '{t9}' != derived '{expected}'"
        )
    # If a flypy code is present, it must decode via the key tables. Bare
    # letter codes that look like YAML booleans (`no` for nuo) must be quoted
    # in the data — PyYAML parses them as False, so flag the scalar instead of
    # silently skipping the consistency check.
    flypy = entry.get("flypy_code")
    if flypy is not None and not isinstance(flypy, str):
        errors.append(
            f"{prefix} t9_disambiguation.syllables[{index}] pinyin '{pinyin}' "
            f"flypy_code must be a quoted string, got {flypy!r} "
            f"(quote letter codes such as 'no')"
        )
    elif flypy:
        if not _is_valid_flypy(pinyin, flypy):
            errors.append(
                f"{prefix} t9_disambiguation.syllables[{index}] pinyin '{pinyin}' "
                f"flypy_code '{flypy}' is inconsistent"
            )
    # If a 14-key fold is present, it must equal the `/14jian` fold of the
    # 双拼 code (only meaningful when the 双拼 code itself is consistent).
    flypy14 = entry.get("flypy_14_code")
    if flypy14 is not None and isinstance(flypy14, str) and flypy14:
        expected14 = flypy14_code(flypy or "")
        if not flypy or expected14 != flypy14:
            errors.append(
                f"{prefix} t9_disambiguation.syllables[{index}] pinyin '{pinyin}' "
                f"flypy_14_code '{flypy14}' is inconsistent"
            )
    # The uppercase-token fold (`/14jian-token`) is checked the same way.
    token14 = entry.get("flypy_14_token")
    if token14 is not None and isinstance(token14, str) and token14:
        expected_token = flypy14_token_code(flypy or "")
        if not flypy or expected_token != token14:
            errors.append(
                f"{prefix} t9_disambiguation.syllables[{index}] pinyin '{pinyin}' "
                f"flypy_14_token '{token14}' is inconsistent"
            )
    return errors


def _is_valid_flypy(pinyin: str, flypy: str) -> bool:
    """Whether [flypy] is the 小鹤双拼 key code of [pinyin].

    Delegates to the generator's `flypy_code` so the shipped data and the
    validator can never disagree (see the module docstring).
    """
    try:
        return flypy == flypy_code(pinyin)
    except ValueError:
        return False
