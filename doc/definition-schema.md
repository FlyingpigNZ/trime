# Trime Definition Schema (three-tier model)

This document defines the YAML shapes for the three definition tiers after the
refactor. It is the source of truth for both the shipped standard resources and
customer-provided packages.

## Tier 1 — Standard catalog (app-shipped)

Files under `app/src/main/assets/shared/standard/`.

### `preset_keys.yaml`

```yaml
preset_keys:
  BackSpace: {label: 退格, repeatable: true, send: BackSpace}
  space: {repeatable: false, functional: false, send: space}
```

Each entry is a `PresetKey`:

| field | type | description |
|---|---|---|
| `label` | string | display label |
| `send` | string | key to send |
| `command` | string | typed command name |
| `option` / `select` / `toggle` / `commit` / `text` / `shift_lock` / `preview` | string | command arguments |
| `states` | list[string] | toggle state labels |
| `sticky` / `repeatable` / `functional` / `slide_cursor` / `slide_delete` | bool | behavior flags |

### `keyboards.yaml`

```yaml
preset_keyboards:
  default:
    name: 預設40鍵
    width: 10
    height: 44
    keys: [...]
  letter:
    __include: /preset_keyboards/default
    ascii_mode: 1
```

`__include` is the only inheritance mechanism. Unknown include targets fail
validation.

### `colors.yaml`

Each scheme is a **self-contained light/dark pair**.

```yaml
preset_color_schemes:
  default:
    light:
      name: 預設／default
      back_color: 0xe4e7e9
      text_color: 0x5a676e
    # dark is optional; missing dark falls back to light
    dark:
      back_color: 0x1e1e1e
      text_color: 0xe0e0e0
```

Legacy flat schemes (without `light:`/`dark:`) are still accepted for
compatibility, but new resources must use the paired shape.

## Tier 2 — Decoration theme

Example: `app/src/main/assets/shared/trime.yaml`.

```yaml
name: 預設
author: osfans
use_standard_preset_keys: true
standard_keyboards: [default, letter, number, symbols]
standard_color_schemes: [default, ink]

style: { ... }
preedit: { ... }
window: { ... }
fallback_colors:
  candidate_text_color: text_color
liquid_keyboard: { ... }
tool_bar: { ... }
```

Rules:

- `standard_keyboards` / `standard_color_schemes` are explicit-by-name
  references into the tier-1 catalog.
- `use_standard_preset_keys: true` opts into the standard preset-key set.
- A theme may still define its own `preset_keyboards`, `preset_keys`, and
  `preset_color_schemes`; those override/extend the selected standard entries.
- No alphabet heuristic is used at runtime. Unbound schemas use the theme's
  `default` keyboard.

## Tier 3 — Schema-layout package (customer-provided)

A package is a zip containing a manifest, a Rime schema, layout fragment(s),
and optional resources.

### `manifest.yaml`

```yaml
schema_id: 14jian
name: 小鹤双拼14键
version: "0.1"
schema_file: 14jian.schema.yaml
theme_file: theme.yaml
layout_files:
  - 14jian.layout.yaml
default_keyboard: 14jian
resources:
  - backgrounds/14jian.png
rime_files:
  - rime/default.yaml
  - rime/cn_dicts/base.dict.yaml
```

### `14jian.schema.yaml`

A normal Rime schema file. It must compile on its own.

### `theme.yaml` (optional)

A tier-2 decoration theme. When `manifest.yaml` lists `theme_file`, the app
uses it as the base decoration theme for the installed package (style,
fallback colors, liquid keyboard, chrome, and color schemes), so the package
can look different from the standard delivery instead of only overriding
keyboards/keys.

### `14jian.layout.yaml`

A layout fragment. A package may contain multiple layout fragments; `manifest.yaml`
lists them in order, and later fragments override earlier ones. Definition
sections (`preset_keys`, `preset_keyboards`, `preset_color_schemes`) are
deep-merged so a schema-specific fragment can reference keyboards/keys defined
in a shared fragment.

A layout fragment may reference standard components and/or define custom
components:

```yaml
standard_keyboards: [default, letter, number, symbols]
use_standard_preset_keys: true

preset_keys:
  14keyqw: {label: " Q W ", send: "q"}

preset_keyboards:
  14jian:
    name: 14键
    keys: [...]

preset_color_schemes:
  custom:
    light:
      back_color: "#123456"
    dark:
      back_color: "#000000"
```

Resources referenced by custom colors (e.g. background images) must be present
in the package `resources` list and are unpacked with the package. Standard
color schemes do not need package resources; they resolve from the standard
catalog.

Rime files required by the schema (dictionaries, auxiliary schemas, Lua
scripts, OpenCC data, `default.yaml`, `symbols_*.yaml`, etc.) are listed in
`rime_files`. They are stored under `rime/` inside the zip; during install the
app copies them into the Rime user data directory (the `rime/` prefix is
stripped) before deploying the schema. This makes a full rime-ice based schema
package self-contained.

## Canonical install workflow

1. User selects a zip/archive containing a schema-layout package (see
   `sample_theme_schemas/minimal-14jian/`).
2. The app extracts the archive, uses the optional package `theme_file` as the
   decoration base, merges the layout fragments on top, and copies package
   resources into the user backgrounds directory so the singleton resource
   managers can access them.
3. The app copies package `rime_files` into the Rime user data directory,
   deploys auxiliary schemas first and then the main schema, adds it to
   `default.custom.yaml` as the default input method, and activates/switches
   to it.
4. Legacy monolithic theme files are not used by this workflow. Runtime
   definitions come from the standard catalog + the installed package + a
   built-in decoration base.

## Validation summary

- Unknown `standard_keyboards` / `standard_color_schemes` names → error
- Unknown `__include` target → error
- Missing `manifest.yaml` or required manifest fields → error
- Unsafe zip paths → error
- Empty `layout_files` → error
- `rime_files` entries must be strings under `rime/` → error
- Color scheme `dark:` may only contain known color keys (validator, item 14)
