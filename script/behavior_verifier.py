#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify keypress behavior in a resolved component definition.

Walks every keyboard and every action field (`click`, `long_click`, swipe_*,
`composing`). If the action value names a preset key, the preset key definition
is checked. If it does not name a preset key, it is treated as literal output.

Also checks keyboard-level references such as `ascii_keyboard` and
`landscape_keyboard`.
"""

from __future__ import annotations

from typing import Any

ACTION_FIELDS = {
    "click",
    "long_click",
    "swipe_up",
    "swipe_down",
    "swipe_left",
    "swipe_right",
    "composing",
}

SPECIAL_SELECTS = {".next", ".last", ".default", ".ascii", ".last_lock"}


def verify(sections: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    preset_keys = sections.get("preset_keys", {})
    keyboards = sections.get("preset_keyboards", {})
    switch_policy = sections.get("keyboard_switch_policy", {})

    # Keyboard-level references.
    for kb_name, kb in keyboards.items():
        if not isinstance(kb, dict):
            errors.append(f"keyboard '{kb_name}' is not a mapping")
            continue
        for ref_field in ("ascii_keyboard", "landscape_keyboard"):
            ref = kb.get(ref_field)
            if isinstance(ref, str) and ref and ref not in keyboards:
                errors.append(
                    f"keyboard '{kb_name}'.{ref_field} references unknown keyboard '{ref}'"
                )

        for index, key in enumerate(kb.get("keys", []) or []):
            if not isinstance(key, dict):
                errors.append(f"keyboard '{kb_name}' key[{index}] is not a mapping")
                continue
            for field in ACTION_FIELDS:
                value = key.get(field)
                if value is None:
                    continue
                if isinstance(value, str) and value in preset_keys:
                    errors.extend(
                        verify_preset_key(
                            preset_keys[value],
                            where=f"keyboard '{kb_name}' key[{index}] {field} '{value}'",
                            keyboards=keyboards,
                        )
                    )
                # Anything else is literal output (number/string not matching a
                # preset key name) and is considered valid.

    # Keyboard switch policy references.
    for field in ("default_keyboard", "ascii_keyboard"):
        target = switch_policy.get(field)
        if isinstance(target, str) and target and target not in keyboards:
            errors.append(f"keyboard_switch_policy.{field} references unknown keyboard '{target}'")
    aux = switch_policy.get("aux", {})
    if isinstance(aux, dict):
        for role, target in aux.items():
            if isinstance(target, str) and target and target not in keyboards:
                errors.append(
                    f"keyboard_switch_policy.aux.{role} references unknown keyboard '{target}'"
                )

    # style.keyboards list references.
    style = sections.get("style", {})
    style_keyboards = style.get("keyboards") if isinstance(style, dict) else None
    if isinstance(style_keyboards, list):
        for name in style_keyboards:
            if isinstance(name, str) and name not in keyboards and name != ".default":
                errors.append(f"style.keyboards references unknown keyboard '{name}'")

    return errors


def verify_preset_key(
    definition: Any,
    where: str,
    keyboards: dict[str, Any] | None = None,
) -> list[str]:
    errors: list[str] = []
    if not isinstance(definition, dict):
        errors.append(f"{where}: preset key definition is not a mapping")
        return errors

    select = definition.get("select")
    if select is not None:
        if not isinstance(select, str) or not select:
            errors.append(f"{where}: 'select' must be a non-empty string")
        elif select not in SPECIAL_SELECTS and keyboards is not None and select not in keyboards:
            errors.append(f"{where}: 'select' references unknown keyboard '{select}'")

    toggle = definition.get("toggle")
    if toggle is not None:
        if not isinstance(toggle, str) or not toggle:
            errors.append(f"{where}: 'toggle' must be a non-empty string")
        states = definition.get("states")
        if not isinstance(states, list) or not states:
            errors.append(f"{where}: toggle '{toggle}' must have a non-empty 'states' list")

    command = definition.get("command")
    if command is not None and (not isinstance(command, str) or not command):
        errors.append(f"{where}: 'command' must be a non-empty string")

    return errors
