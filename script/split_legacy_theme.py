#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2015 - 2026 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Split the legacy 简纯+14键.trime.yaml monolith into tier-2/tier-3 files.

Reads:
  sample_theme_schemas/简纯+14键.trime.yaml   (legacy monolith)
  sample_theme_schemas/14jian.schema.yaml     (full Rime schema)

Writes:
  sample_theme_schemas/简纯+14键/theme.yaml
  sample_theme_schemas/简纯+14键/manifest.yaml
  sample_theme_schemas/简纯+14键/14jian.schema.yaml
  sample_theme_schemas/简纯+14键/14jian.layout.yaml

The conversion expands the legacy `conf`/`styl` indirection and `__patch`
operations into explicit fields, then partitions the definition set:
  - Tier 2 theme: decoration (style/colors/liquid) + general keyboards/keys.
  - Tier 3 package: schema-specific keyboards/keys + the Rime schema.
"""

from __future__ import annotations

import copy
import shutil
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "sample_theme_schemas/简纯+14键.trime.yaml"
SCHEMA_SRC = ROOT / "sample_theme_schemas/14jian.schema.yaml"
OUT_DIR = ROOT / "sample_theme_schemas/简纯+14键"

# Schema-specific keyboards in the legacy monolith.
SCHEMA_KEYBOARDS = {
    "14jian",
    "letter_14jian",
    "14number",
    "14numberen",
    "14symbols",
    "14symbolsen",
    "letter_18jian",
}

# Preset keys that only belong to the schema-specific keyboards.
SCHEMA_KEYS = {
    "14keyqw", "14keyer", "14keyty", "14keyui", "14keyop",
    "14keyas", "14keydf", "14keygh", "14keyjk", "14keyl",
    "14keyzx", "14keycv", "14keybn", "14keym",
    "14Keyboard_number", "14Keyboard_symbols",
    "14Keyboard_numberen", "14Keyboard_symbolsen",
    "Keyboard_14jian", "Keyboard_letter_14jian",
    "Back14", "Back14en", "fenhao",
}


def deep_merge(base: dict, override: dict) -> dict:
    """Merge override into base; dicts deep-merge, other values are replaced."""
    out = dict(base)
    for key, value in override.items():
        if key in out and isinstance(out[key], dict) and isinstance(value, dict):
            out[key] = deep_merge(out[key], value)
        else:
            out[key] = copy.deepcopy(value)
    return out


class LegacyResolver:
    """Resolves legacy `conf`/`styl` includes and `__patch` operations."""

    def __init__(self, data: dict):
        self.data = data
        self.conf = data.get("conf") or {}
        self.styl = data.get("styl") or {}
        self.keyboards = data["preset_keyboards"]

    def lookup(self, path: str) -> object:
        if path.startswith("conf/"):
            return self.conf[path[len("conf/"):]]
        if path.startswith("styl/"):
            return self.styl[path[len("styl/"):]]
        if path.startswith("preset_keyboards/"):
            return self.keyboards[path[len("preset_keyboards/"):]]
        raise ValueError(f"unknown include prefix in {path}")

    def resolve_node(self, node: object, stack: tuple[str, ...] = ()) -> object:
        """Expand `__include`/`__patch` inside arbitrary YAML nodes."""
        if isinstance(node, dict):
            inc = node.get("__include")
            patch = node.get("__patch")
            rest = {k: v for k, v in node.items() if k not in ("__include", "__patch")}
            if isinstance(inc, str):
                if inc in stack:
                    raise ValueError(f"circular include: {' -> '.join(stack + (inc,))}")
                base = self.resolve_node(self.lookup(inc), stack + (inc,))
                if isinstance(base, dict):
                    node = deep_merge(base, rest)
                else:
                    if rest:
                        # Unusual but safe: a scalar include with extra fields.
                        merged = {k: v for k, v in rest.items()}
                        merged["__value"] = base
                        node = merged
                    else:
                        return base
            else:
                node = rest
            if patch is not None:
                if not isinstance(node, dict):
                    raise ValueError("__patch on a non-mapping node")
                self.apply_patch(node, patch)
            return {k: self.resolve_node(v, stack) for k, v in node.items()}
        if isinstance(node, list):
            return [self.resolve_node(item, stack) for item in node]
        return copy.deepcopy(node)

    def apply_mapping_patch(self, target: dict, patch: object) -> None:
        """Deep-merge a mapping patch; `keys/+` appends to the key list."""
        patch = self.resolve_node(patch)
        if not isinstance(patch, dict):
            raise ValueError(f"mapping patch must be a mapping, got {patch!r}")
        if "keys/+" in patch:
            append_items = patch.pop("keys/+")
            if not isinstance(append_items, list):
                append_items = [append_items]
            keys = target.setdefault("keys", [])
            if not isinstance(keys, list):
                keys = []
                target["keys"] = keys
            keys.extend(copy.deepcopy(append_items))
        merged = deep_merge(target, patch)
        target.clear()
        target.update(merged)

    @staticmethod
    def set_path(obj: dict, path: str, value: object) -> None:
        parts = path.split("/")
        cur: object = obj
        for part in parts[:-1]:
            if part == "keys":
                cur = cur.setdefault("keys", [])  # type: ignore[union-attr]
            elif part.startswith("@"):
                assert isinstance(cur, list)
                index = int(part[1:])
                while len(cur) <= index:
                    cur.append({})
                cur = cur[index]
            else:
                assert isinstance(cur, dict)
                nxt = cur.get(part)
                if nxt is None:
                    nxt = {}
                    cur[part] = nxt
                cur = nxt
        assert isinstance(cur, dict)
        cur[parts[-1]] = copy.deepcopy(value)

    def apply_patch(self, target: dict, patch: object) -> None:
        patch = self.resolve_node(patch)
        if isinstance(patch, str):
            self.apply_mapping_patch(target, self.lookup(patch))
        elif isinstance(patch, dict):
            for path, value in patch.items():
                self.set_path(target, path, value)
        else:
            raise ValueError(f"bad patch spec: {patch!r}")

    def apply_keyboard_patches(self, target: dict, patch_spec: object) -> None:
        if patch_spec is None:
            return
        if isinstance(patch_spec, str):
            self.apply_mapping_patch(target, self.lookup(patch_spec))
        elif isinstance(patch_spec, list):
            for patch in patch_spec:
                self.apply_patch(target, patch)
        else:
            raise ValueError(f"bad __patch spec: {patch_spec!r}")

    def resolve_keyboard(self, name: str, stack: tuple[str, ...] = ()) -> dict:
        if name in stack:
            raise ValueError(f"circular keyboard include: {' -> '.join(stack + (name,))}")
        node = self.keyboards[name]
        inc = node.get("__include")
        own = {k: v for k, v in node.items() if k not in ("__include", "__patch")}
        if isinstance(inc, str) and (inc.startswith("conf/") or inc.startswith("styl/")):
            base = self.resolve_node(self.lookup(inc))
            if not isinstance(base, dict):
                raise ValueError(f"keyboard include {inc} must be a mapping")
            merged = deep_merge(base, own)
        elif isinstance(inc, str):
            target = inc.rsplit("/", 1)[-1]
            if target not in self.keyboards:
                raise ValueError(f"unknown keyboard include {inc}")
            merged = deep_merge(self.resolve_keyboard(target, stack + (name,)), own)
        else:
            merged = own
        self.apply_keyboard_patches(merged, node.get("__patch"))
        return merged


def hex_color(value: object) -> object:
    if isinstance(value, int):
        if value <= 0xFFFFFF:
            return f"0x{value:06x}"
        return f"0x{value:08x}"
    return value


def scheme_palette(scheme: dict) -> dict:
    out = {}
    for key, value in scheme.items():
        if key in ("light_scheme", "dark_scheme", "name"):
            continue
        out[key] = hex_color(value)
    return out


def convert_colors(data: dict) -> dict:
    schemes = data["preset_color_schemes"]
    colors = {}
    for name, scheme in schemes.items():
        light = scheme_palette(scheme)
        dark_ref = scheme.get("dark_scheme")
        light_ref = scheme.get("light_scheme")
        if dark_ref and dark_ref in schemes:
            dark = scheme_palette(schemes[dark_ref])
        elif light_ref and light_ref in schemes:
            light = scheme_palette(schemes[light_ref])
            dark = scheme_palette(scheme)
        else:
            dark = dict(light)
        if dark_ref and dark_ref in schemes:
            light_meta = scheme
            dark_meta = schemes[dark_ref]
        elif light_ref and light_ref in schemes:
            light_meta = schemes[light_ref]
            dark_meta = scheme
        else:
            light_meta = scheme
            dark_meta = scheme
        light_palette = {"name": light_meta.get("name", name)}
        dark_palette = {"name": dark_meta.get("name", name)}
        if light_meta.get("author"):
            light_palette["author"] = light_meta["author"]
        if dark_meta.get("author"):
            dark_palette["author"] = dark_meta["author"]
        light_palette.update(light)
        dark_palette.update(dark)
        colors[name] = {"light": light_palette, "dark": dark_palette}
    return colors


def normalize_keyboards(data: dict, resolver: LegacyResolver) -> dict:
    normalized = {}
    for name in data["preset_keyboards"]:
        keyboard = resolver.resolve_keyboard(name)
        keyboard.pop("__include", None)
        keyboard.pop("__patch", None)
        if isinstance(keyboard.get("keys"), list):
            keyboard["keys"] = [resolver.resolve_node(k) for k in keyboard["keys"]]
        normalized[name] = keyboard
    return normalized


ACTION_FIELDS = {
    "click", "long_click", "swipe_up", "swipe_down",
    "swipe_left", "swipe_right", "composing",
}


def collect_action_values(keyboard: dict) -> set[str]:
    values: set[str] = set()
    for key in keyboard.get("keys") or []:
        if not isinstance(key, dict):
            continue
        for field, value in key.items():
            if field in ACTION_FIELDS and isinstance(value, str):
                values.add(value)
    return values


def dump(obj: object) -> str:
    return yaml.safe_dump(obj, allow_unicode=True, sort_keys=False, width=1000)


def main() -> int:
    if not SRC.exists():
        print(f"Missing source monolith: {SRC}", file=__import__("sys").stderr)
        return 1
    with open(SRC, encoding="utf-8") as f:
        data = yaml.safe_load(f)

    resolver = LegacyResolver(data)
    normalized = normalize_keyboards(data, resolver)

    # Sanity check: no legacy indirection should remain.
    for name, keyboard in normalized.items():
        if "__include" in keyboard or "__patch" in keyboard:
            raise RuntimeError(f"residual legacy field in keyboard {name}")
        for index, key in enumerate(keyboard.get("keys") or []):
            if isinstance(key, dict) and ("__include" in key or "__patch" in key):
                raise RuntimeError(f"residual legacy field in {name} keys[{index}]")

    colors = convert_colors(data)
    all_keyboards = set(data["preset_keyboards"])
    general_keyboards = all_keyboards - SCHEMA_KEYBOARDS
    if not SCHEMA_KEYBOARDS.issubset(all_keyboards):
        raise RuntimeError(f"missing schema keyboards: {SCHEMA_KEYBOARDS - all_keyboards}")

    standard_keys_path = ROOT / "app/src/main/assets/shared/standard/preset_keys.yaml"
    with open(standard_keys_path, encoding="utf-8") as f:
        standard_keys = set(yaml.safe_load(f)["preset_keys"])
    schema_used = set()
    for name in SCHEMA_KEYBOARDS:
        schema_used |= collect_action_values(normalized[name])
    # Make the layout self-contained for every non-standard key the schema
    # keyboards reference; the theme may also define these, and duplicates are
    # harmless (the layout wins when merged).
    schema_extra = {
        key for key in schema_used
        if key in data["preset_keys"] and key not in standard_keys
    }
    layout_key_names = SCHEMA_KEYS | schema_extra
    theme_keys = {k: v for k, v in data["preset_keys"].items() if k not in SCHEMA_KEYS}
    layout_keys = {k: v for k, v in data["preset_keys"].items() if k in layout_key_names}

    theme = {
        "name": data["name"],
        "author": data["author"],
        "use_standard_preset_keys": True,
        "standard_keyboards": ["default", "letter", "number", "symbols"],
        "standard_color_schemes": ["default"],
        "style": resolver.resolve_node(data["style"]),
        "fallback_colors": resolver.resolve_node(data["fallback_colors"]),
        "preset_color_schemes": colors,
        "liquid_keyboard": resolver.resolve_node(data["liquid_keyboard"]),
        "preset_keyboards": {name: normalized[name] for name in sorted(general_keyboards)},
        "preset_keys": theme_keys,
    }

    layout = {
        "name": "14键布局",
        "author": data["author"],
        "style": {},
        "use_standard_preset_keys": True,
        "standard_keyboards": ["default", "letter", "number", "symbols"],
        "standard_color_schemes": ["default"],
        "preset_keyboards": {name: normalized[name] for name in sorted(SCHEMA_KEYBOARDS)},
        "preset_keys": layout_keys,
    }

    manifest = {
        "schema_id": "14jian",
        "name": data["name"],
        "version": "0.1",
        "schema_file": "14jian.schema.yaml",
        "layout_files": ["14jian.layout.yaml"],
        "default_keyboard": "14jian",
        "resources": [],
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "theme.yaml").write_text(
        "# Tier-2 decoration theme split from 简纯+14键.trime.yaml\n"
        "# Generated by script/split_legacy_theme.py\n" + dump(theme),
        encoding="utf-8",
    )
    (OUT_DIR / "14jian.layout.yaml").write_text(
        "# Tier-3 schema layout split from 简纯+14键.trime.yaml\n"
        "# Generated by script/split_legacy_theme.py\n" + dump(layout),
        encoding="utf-8",
    )
    (OUT_DIR / "manifest.yaml").write_text(
        "# Schema-layout package manifest (full 简纯+14键 conversion)\n"
        + dump(manifest),
        encoding="utf-8",
    )
    if SCHEMA_SRC.exists():
        shutil.copyfile(SCHEMA_SRC, OUT_DIR / "14jian.schema.yaml")
    else:
        print(f"Warning: {SCHEMA_SRC} not found; schema file not copied", file=__import__("sys").stderr)

    print(f"Wrote {OUT_DIR}")
    print(f"theme keyboards: {len(general_keyboards)}, layout keyboards: {len(SCHEMA_KEYBOARDS)}")
    print(f"theme keys: {len(theme_keys)}, layout keys: {len(layout_keys)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
