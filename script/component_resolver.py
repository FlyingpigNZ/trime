#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reference prototype for the Trime component definition model.

This is a pure-Python prototype of the composition semantics described in
`doc/component-model.md`:

  - components are composed in order
  - later components override earlier ones
  - operations are `add`, `override`, `remove`
  - unknown targets fail validation

It is not yet wired into the Android runtime. It exists to validate the
semantics and to serve as a reference for the Kotlin implementation.
"""

from __future__ import annotations

import copy
import sys
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parent.parent
STANDARD_DIR = ROOT / "app/src/main/assets/shared/standard"

SECTION_FILES = {
    "keyboard": "keyboard.yaml",
    "behavior": "behavior.yaml",
    "color": "color.yaml",
    "style": "style.yaml",
    "resources": "resources.yaml",
}

# YAML section keys produced by each component file.
FILE_SECTIONS = {
    "preset_keys.yaml": "preset_keys",
    "keyboards.yaml": "preset_keyboards",
    "colors.yaml": "preset_color_schemes",
}


class ComponentError(ValueError):
    """Raised for invalid component composition."""


def resolve_color_schemes(data: dict[str, Any]) -> dict[str, Any]:
    """Convert flat `colors` + `color_schemes` into inline `preset_color_schemes`."""
    colors = data.get("colors")
    schemes = data.get("color_schemes")
    if not isinstance(colors, dict) or not isinstance(schemes, dict):
        return data
    if not isinstance(colors, dict) or not all(isinstance(v, dict) for v in colors.values()):
        raise ComponentError("'colors' must be a mapping of palette name to palette mapping")
    resolved: dict[str, Any] = {}
    for name, pair in schemes.items():
        if not isinstance(pair, dict):
            raise ComponentError(f"color_schemes.{name}: must be a mapping")
        light_name = pair.get("light")
        dark_name = pair.get("dark", light_name)
        if not isinstance(light_name, str) or light_name not in colors:
            raise ComponentError(f"color_schemes.{name}: unknown light palette '{light_name}'")
        if dark_name is not None and dark_name not in colors:
            raise ComponentError(f"color_schemes.{name}: unknown dark palette '{dark_name}'")
        light = colors[light_name]
        dark = colors.get(dark_name, light) if dark_name is not None else light
        scheme = {"light": light, "dark": dark}
        if "name" in pair:
            scheme["name"] = pair["name"]
        if "author" in pair:
            scheme["author"] = pair["author"]
        resolved[name] = scheme
    out = dict(data)
    out["preset_color_schemes"] = resolved
    return out


def deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    out = dict(base)
    for key, value in override.items():
        if key in out and isinstance(out[key], dict) and isinstance(value, dict):
            out[key] = deep_merge(out[key], value)
        else:
            out[key] = copy.deepcopy(value)
    return out


def load_yaml(path: Path) -> dict[str, Any]:
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        raise ComponentError(f"{path}: invalid YAML: {exc}") from exc
    if not isinstance(data, dict):
        raise ComponentError(f"{path}: component file must be a YAML mapping")
    return data


class ComponentResolver:
    def __init__(self, manifest_dir: Path):
        self.manifest_dir = manifest_dir
        self.sections: dict[str, dict[str, Any]] = {
            "preset_keys": {},
            "preset_keyboards": {},
            "preset_color_schemes": {},
            "switches": {},
            "keyboard_switch_policy": {},
            "style": {},
            "preedit": {},
            "window": {},
            "tool_bar": {},
            "liquid_keyboard": {},
            "fallback_colors": {},
            "resources": {},
        }
        self.use_standard_preset_keys = False
        self.standard_keyboards: list[str] = []
        self.standard_color_schemes: list[str] = []

    # ── standard component ────────────────────────────────────────────────
    def _expand_standard_keyboards(
        self, selected: list[str], all_keyboards: dict[str, Any]
    ) -> dict[str, Any]:
        expanded: dict[str, Any] = {}
        seen: set[str] = set()

        def add(name: str) -> None:
            if name in seen or name not in all_keyboards:
                return
            seen.add(name)
            node = all_keyboards[name]
            include = node.get("__include") if isinstance(node, dict) else None
            if isinstance(include, str):
                add(include.rsplit("/", 1)[-1])
            expanded[name] = node

        for name in selected:
            add(name)
        return expanded

    def load_standard(self) -> None:
        # Self-contained packages carry their own standard/ component. Fall
        # back to the app-shipped standard only for legacy manifests that
        # still use the magic `standard` reference without a local directory.
        standard_dir = self.manifest_dir / "standard"
        if not standard_dir.is_dir():
            standard_dir = STANDARD_DIR
        keys_data = load_yaml(standard_dir / "preset_keys.yaml")
        keyboards_data = load_yaml(standard_dir / "keyboards.yaml")
        colors_data = load_yaml(standard_dir / "colors.yaml")

        if self.use_standard_preset_keys:
            self.sections["preset_keys"] = deep_merge(
                self.sections["preset_keys"], keys_data.get("preset_keys", {})
            )
        standard_keyboards = self._expand_standard_keyboards(
            self.standard_keyboards, keyboards_data.get("preset_keyboards", {})
        )
        self.sections["preset_keyboards"] = deep_merge(
            self.sections["preset_keyboards"], standard_keyboards
        )
        standard_colors = {
            name: colors_data["preset_color_schemes"][name]
            for name in self.standard_color_schemes
            if name in colors_data.get("preset_color_schemes", {})
        }
        self.sections["preset_color_schemes"] = deep_merge(
            self.sections["preset_color_schemes"], standard_colors
        )

    # ── generic component loading ─────────────────────────────────────────
    def load_component_dir(self, directory: Path) -> None:
        if not directory.is_dir():
            raise ComponentError(f"component directory not found: {directory}")
        for section, file_name in SECTION_FILES.items():
            path = directory / file_name
            if not path.exists():
                continue
            data = load_yaml(path)
            self.apply_component_data(data)

    def apply_component_data(self, data: dict[str, Any]) -> None:
        data = resolve_color_schemes(data)
        section_map = {
            "preset_keys": data.get("preset_keys", {}),
            "preset_keyboards": data.get("preset_keyboards", {}),
            "preset_color_schemes": data.get("preset_color_schemes", {}),
            "switches": data.get("switches", {}),
            "keyboard_switch_policy": data.get("keyboard_switch_policy", {}),
            "fallback_colors": data.get("fallback_colors", {}),
            "liquid_keyboard": data.get("liquid_keyboard", {}),
            "style": data.get("style", {}),
            "preedit": data.get("preedit", {}),
            "window": data.get("window", {}),
            "tool_bar": data.get("tool_bar", {}),
            "resources": data.get("resources", {}),
        }
        named_sections = {"preset_keys", "preset_keyboards", "preset_color_schemes"}
        for section, values in section_map.items():
            if not values:
                continue
            if not isinstance(values, dict):
                raise ComponentError(f"section '{section}' must be a mapping")
            if section in named_sections:
                # Named entities replace same-name entries rather than deep-merge.
                merged = dict(self.sections[section])
                merged.update(values)
                self.sections[section] = merged
            else:
                self.sections[section] = deep_merge(self.sections[section], values)

    # ── operations ────────────────────────────────────────────────────────
    @staticmethod
    def _require_mapping(value: Any, what: str) -> dict[str, Any]:
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise ComponentError(f"{what} must be a mapping")
        return value

    def apply_operations(
        self,
        section: str,
        ops: dict[str, Any] | None,
        context: str,
    ) -> None:
        if not ops:
            return
        target = self.sections.setdefault(section, {})

        for name, value in self._require_mapping(ops.get("add"), f"{context}.add").items():
            if name in target:
                raise ComponentError(
                    f"{context}.add: '{name}' already exists in {section}; use override"
                )
            target[name] = copy.deepcopy(value)

        for name, value in self._require_mapping(
            ops.get("override"), f"{context}.override"
        ).items():
            if name not in target:
                raise ComponentError(
                    f"{context}.override: '{name}' does not exist in {section}; use add"
                )
            target[name] = copy.deepcopy(value)

        for name in ops.get("remove", []):
            if name not in target:
                raise ComponentError(
                    f"{context}.remove: '{name}' does not exist in {section}"
                )
            del target[name]

    # ── manifest component entries ────────────────────────────────────────
    def resolve_entry(self, entry: Any) -> None:
        if isinstance(entry, str):
            if entry == "standard":
                self.load_standard()
                return
            # A string can name a component directory next to the manifest.
            path = (self.manifest_dir / entry).resolve()
            self.load_component_dir(path)
            return

        if not isinstance(entry, dict) or len(entry) != 1:
            raise ComponentError(
                f"component entry must be a string or single-key mapping: {entry!r}"
            )

        kind, spec = next(iter(entry.items()))
        if kind == "schema":
            # Schema files are not merged into Theme sections; validated elsewhere.
            return
        if kind == "resources":
            self.apply_operations("resources", spec, "resources")
            return

        section_map = {
            "keyboard": "preset_keyboards",
            "behavior": "preset_keys",
            "color": "preset_color_schemes",
        }
        if kind == "style":
            self._apply_style(spec)
            return

        section = section_map.get(kind)
        if section is None:
            raise ComponentError(f"unknown component kind '{kind}'")

        if isinstance(spec, str):
            data = load_yaml((self.manifest_dir / spec).resolve())
            self.apply_component_data(data)
            return
        if isinstance(spec, dict) and ("file" in spec or "files" in spec):
            files = spec["files"] if "files" in spec else [spec["file"]]
            for file_name in files:
                data = load_yaml((self.manifest_dir / file_name).resolve())
                self.apply_component_data(data)
            self.apply_operations(section, spec, kind)
            return
        if isinstance(spec, dict):
            self.apply_operations(section, spec, kind)
            return

        raise ComponentError(f"invalid '{kind}' component spec: {spec!r}")

    def _apply_style(self, spec: Any) -> None:
        if isinstance(spec, str):
            data = load_yaml((self.manifest_dir / spec).resolve())
            self.apply_component_data(data)
            return
        if not isinstance(spec, dict):
            raise ComponentError(f"invalid style component spec: {spec!r}")
        if "file" in spec or "files" in spec:
            files = spec["files"] if "files" in spec else [spec["file"]]
            for file_name in files:
                data = load_yaml((self.manifest_dir / file_name).resolve())
                self.apply_component_data(data)
        overrides = self._require_mapping(spec.get("override"), "style.override")
        if overrides:
            self.sections["style"] = deep_merge(self.sections["style"], overrides)

    # ── top level ─────────────────────────────────────────────────────────
    def resolve_manifest(self, manifest: dict[str, Any]) -> dict[str, Any]:
        components = manifest.get("components")
        if not isinstance(components, list) or not components:
            raise ComponentError("manifest must contain a non-empty 'components' list")
        self.use_standard_preset_keys = bool(manifest.get("use_standard_preset_keys", False))
        self.standard_keyboards = list(manifest.get("standard_keyboards", []) or [])
        self.standard_color_schemes = list(manifest.get("standard_color_schemes", []) or [])
        for entry in components:
            self.resolve_entry(entry)
        return self.sections


def resolve_file(manifest_path: Path) -> dict[str, Any]:
    manifest = load_yaml(manifest_path)
    resolver = ComponentResolver(manifest_path.parent)
    return resolver.resolve_manifest(manifest)


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        print(__doc__)
        return 0
    try:
        sections = resolve_file(Path(argv[0]))
    except ComponentError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(yaml.safe_dump(sections, allow_unicode=True, sort_keys=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
