#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate color literal formats in resolved component sections."""

from __future__ import annotations

from typing import Any

HEX_PREFIXES = ("0x", "0X", "#")

# Color keys the runtime resolves through its builtin fallback table even when
# a self-contained package does not redefine them in every color scheme.
BUILTIN_FALLBACK_KEYS = {
    "candidate_text_color",
    "comment_text_color",
    "border_color",
    "candidate_separator_color",
    "hilited_text_color",
    "hilited_back_color",
    "hilited_candidate_text_color",
    "hilited_candidate_back_color",
    "hilited_candidate_button_color",
    "hilited_label_color",
    "hilited_comment_text_color",
    "hilited_key_back_color",
    "hilited_key_text_color",
    "hilited_key_symbol_color",
    "hilited_off_key_back_color",
    "hilited_on_key_back_color",
    "hilited_off_key_text_color",
    "hilited_on_key_text_color",
    "key_back_color",
    "key_border_color",
    "key_text_color",
    "key_symbol_color",
    "label_color",
    "off_key_back_color",
    "off_key_text_color",
    "on_key_back_color",
    "on_key_text_color",
    "popup_back_color",
    "popup_text_color",
    "hilited_popup_back_color",
    "hilited_popup_text_color",
    "shadow_color",
    "root_background",
    "candidate_background",
    "keyboard_back_color",
    "keyboard_background",
    "liquid_keyboard_background",
    "text_back_color",
    "long_text_color",
    "long_text_back_color",
}


def is_hex_color(value: str) -> bool:
    if not isinstance(value, str):
        return False
    for prefix in HEX_PREFIXES:
        if value.startswith(prefix):
            body = value[len(prefix):]
            return 1 <= len(body) <= 8 and all(
                c in "0123456789abcdefABCDEF" for c in body
            )
    return False


def _collect_palette_keys(sections: dict[str, Any], known: set[str]) -> list[str]:
    errors: list[str] = []
    schemes = sections.get("preset_color_schemes", {})
    for scheme_name, scheme in schemes.items():
        if not isinstance(scheme, dict):
            errors.append(f"preset_color_schemes.{scheme_name}: must be a mapping")
            continue
        palettes = []
        if isinstance(scheme.get("light"), dict):
            palettes.append(scheme["light"])
        if isinstance(scheme.get("dark"), dict):
            palettes.append(scheme["dark"])
        if not palettes:
            palettes.append(scheme)
        for palette in palettes:
            for key, value in palette.items():
                if key in ("name", "author"):
                    continue
                known.add(key)
                if not is_hex_color(value):
                    errors.append(
                        f"preset_color_schemes.{scheme_name}.{key}: invalid color '{value}'"
                    )
    return errors


def _validate_fallback(sections: dict[str, Any], known: set[str]) -> list[str]:
    errors: list[str] = []
    fallback = sections.get("fallback_colors", {})
    for key, value in fallback.items():
        known.add(key)
        if not is_hex_color(value) and value not in known:
            errors.append(f"fallback_colors.{key}: invalid color reference '{value}'")
    return errors


def _validate_tool_bar(sections: dict[str, Any], known: set[str]) -> list[str]:
    errors: list[str] = []

    def walk(node: Any, path: str) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                child = f"{path}.{key}"
                if key in ("normal", "highlight"):
                    if isinstance(value, str) and not is_hex_color(value) and value not in known:
                        errors.append(f"{child}: invalid color '{value}'")
                walk(value, child)
        elif isinstance(node, list):
            for i, item in enumerate(node):
                walk(item, f"{path}[{i}]")

    walk(sections.get("tool_bar", {}), "tool_bar")
    return errors


def verify(sections: dict[str, Any]) -> list[str]:
    known = set(BUILTIN_FALLBACK_KEYS)
    errors: list[str] = []
    errors += _collect_palette_keys(sections, known)
    errors += _validate_fallback(sections, known)
    errors += _validate_tool_bar(sections, known)
    return errors
