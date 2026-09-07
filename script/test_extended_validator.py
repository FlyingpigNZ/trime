#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for script/extended_validator.py."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from extended_validator import validate_extended_file


def write(tmp: Path, content: str, name: str = "wanxiang_t9.extended.yaml") -> Path:
    path = tmp / name
    path.write_text(content, encoding="utf-8")
    return path


class ExtendedValidatorTest(unittest.TestCase):
    def test_valid_full_file(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
schema_id: wanxiang_t9
tool_bar:
  __replace: false
  button_spacing: 5
t9_disambiguation:
  enabled: true
  input_method: full
  syllables:
    - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42'}
""",
            )
            self.assertEqual(validate_extended_file(path), [])

    def test_schema_id_mismatch_file_name(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            # The file name says wanxiang_t9 but the declared schema_id is
            # wanxiang_flypy_t9: the validator must flag the mismatch.
            path = write(
                Path(d),
                """
schema_id: wanxiang_flypy_t9
tool_bar:
  __replace: false
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(
                any("schema_id 'wanxiang_flypy_t9' does not match file name" in e for e in errors)
            )

    def test_valid_flypy_keys(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
schema_id: wanxiang_flypy_t9
t9_disambiguation:
  input_method: flypy
  syllables:
    - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42'}
  flypy_keys:
    initials:
      zh: v
    finals:
      ao: c
""",
                name="wanxiang_flypy_t9.extended.yaml",
            )
            self.assertEqual(validate_extended_file(path), [])

    def test_missing_all_sections(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(Path(d), "some_key: value\n")
            errors = validate_extended_file(path)
            self.assertTrue(any("at least one of" in e for e in errors))

    def test_bad_replace_type(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
tool_bar:
  __replace: not-a-bool
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("__replace must be a boolean" in e for e in errors))

    def test_bad_input_method(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
t9_disambiguation:
  input_method: sogou
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("input_method must be" in e for e in errors))

    def test_inconsistent_t9_code(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
t9_disambiguation:
  syllables:
    - {pinyin: hao, t9_code: '111', flypy_code: hc, flypy_t9_code: '42'}
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("t9_code '111' != derived" in e for e in errors))

    def test_inconsistent_flypy_code(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
t9_disambiguation:
  syllables:
    - {pinyin: hao, t9_code: '426', flypy_code: xx, flypy_t9_code: '42'}
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("flypy_code 'xx' is inconsistent" in e for e in errors))

    def test_bad_yaml(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(Path(d), "tool_bar: [unclosed\n")
            errors = validate_extended_file(path)
            self.assertTrue(any("invalid YAML" in e for e in errors))

    def test_valid_flypy14_file(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
schema_id: wanxiang_14jian
t9_disambiguation:
  enabled: true
  input_method: flypy14
  keyboard: 14jian
  syllables:
    - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42', flypy_14_code: gc, flypy_14_token: HL}
    - {pinyin: zhong, t9_code: '94664', flypy_code: vs, flypy_t9_code: '87', flypy_14_code: ca, flypy_14_token: LF}
""",
                name="wanxiang_14jian.extended.yaml",
            )
            self.assertEqual(validate_extended_file(path), [])

    def test_flypy14_missing_fold_code(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
t9_disambiguation:
  input_method: flypy14
  syllables:
    - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42'}
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("requires 'flypy_code'" in e for e in errors))
            self.assertTrue(any("'flypy_14_token'" in e for e in errors))

    def test_inconsistent_flypy14_code(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
t9_disambiguation:
  input_method: flypy14
  syllables:
    - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42', flypy_14_code: ca, flypy_14_token: HL}
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("flypy_14_code 'ca' is inconsistent" in e for e in errors))

    def test_inconsistent_flypy14_token(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
t9_disambiguation:
  input_method: flypy14
  syllables:
    - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42', flypy_14_code: gc, flypy_14_token: CA}
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("flypy_14_token 'CA' is inconsistent" in e for e in errors))

    def test_flypy14_malformed_syllable_does_not_crash(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            path = write(
                Path(d),
                """
t9_disambiguation:
  input_method: flypy14
  syllables:
    - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42', flypy_14_code: gc, flypy_14_token: HL}
    - not_a_mapping
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("syllables[1] must be a mapping" in e for e in errors))

    def test_unquoted_boolean_flypy_code_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            # PyYAML parses bare `no` as False (nuo's 双拼 code) — the validator
            # must flag the scalar instead of silently skipping the check.
            path = write(
                Path(d),
                """
t9_disambiguation:
  syllables:
    - {pinyin: nuo, t9_code: '686', flypy_code: no, flypy_t9_code: '66'}
""",
            )
            errors = validate_extended_file(path)
            self.assertTrue(any("flypy_code must be a quoted string" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
