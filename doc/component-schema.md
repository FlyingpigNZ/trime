# Component Definition Schema

This document defines the precise file layout and manifest schema for the
component model described in `doc/component-model.md`.

Status: **draft** — implemented by the Python prototype in
`script/component_resolver.py` and the Kotlin port in
`app/src/main/java/com/osfans/trime/data/theme/component/`.
Machine-readable schema: `doc/component-schema.json`.

> Historical schema draft. The implemented self-contained package shape is
> documented in `doc/definition-schema.md`; references to `shared-aux`,
> `standard`, and schema-layout manifest fields below are preserved for
> history.

---

## 1. Component directory layout

A reusable component is a directory. Recognized files:

| File | Sections it may contain |
|---|---|
| `component.yaml` | `name`, `description`, `source`, `version` (metadata only) |
| `keyboard.yaml` | `preset_keyboards` |
| `behavior.yaml` | `preset_keys`, `switches`, `keyboard_switch_policy` |
| `style.yaml` | `style`, `preedit`, `window`, `tool_bar`, `liquid_keyboard` |
| `color.yaml` | `preset_color_schemes`, `fallback_colors` |
| `resources.yaml` | `resources` |

All files are optional. An empty component is valid but usually useless.

### Example

```text
shared-aux/
  component.yaml
  keyboard.yaml
  behavior.yaml
  style.yaml
  color.yaml
  resources.yaml
```

---

## 2. Component manifest (`manifest.yaml`)

A concrete input method / theme is described by a `manifest.yaml`:

```yaml
name: 简纯+14键
author: amzxyz
version: "1.0"

# Optional: schema-package fields (same as today's package manifest).
schema_id: 14jian
schema_file: 14jian.schema.yaml
layout_files: [14jian.layout.yaml]
default_keyboard: 14jian
resources: []

components:
  - standard           # local component, included in the package zip
  - shared-aux         # local component, included in the package zip
  - schema:
      file: 14jian.schema.yaml
  - keyboard:
      file: 14jian.layout.yaml
      add: { ... }
      override: { ... }
      remove: [ ... ]
  - behavior:
      file: behavior.yaml
      add: { ... }
      override: { ... }
      remove: [ ... ]
  - color:
      file: color.yaml
      add: { ... }
      override: { ... }
      remove: [ ... ]
  - style:
      file: style.yaml
      override: { ... }
  - resources:
      add: [ ... ]
      remove: [ ... ]
```

> **Self-contained packages:** every string component must be a path that the
> packaging tool includes in the zip. There is no implicit app-shipped
> `standard` component for customer packages; the default app package may use a
> local directory named `standard`.

### 2.1 Top-level fields

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Human-readable input method / theme name |
| `author` | string | no | Author |
| `version` | string | no | Version |
| `schema_id` | string | no | Rime schema id (for schema packages) |
| `schema_file` | string | no | Rime schema file in the package |
| `theme_file` | string | no | Optional tier-2 decoration theme in the package |
| `layout_files` | list[string] | no | Layout fragment files in the package |
| `default_keyboard` | string | no | Default keyboard for the schema |
| `resources` | list[string] | no | Package resources |
| `rime_files` | list[string] | no | Rime shared-data files under `rime/` |
| `components` | list[component-entry] | yes | Ordered composition list |

### 2.2 Component entry

Each entry is either:

1. A **string** naming a local/sibling component directory that is included in
   the package:
   - Any relative directory path, e.g. `shared-aux`, `shared-aux`,
     `components/base`.
   - The name `standard` is not special for customer packages; it is only a
     conventional local directory inside the app-shipped default package.
2. A **single-key mapping**:

| Key | Spec | Semantics |
|---|---|---|
| `schema` | `{file: string}` | Adds a Rime schema file (not merged into Theme sections) |
| `keyboard` | file/operations | Composes `preset_keyboards` |
| `behavior` | file/operations | Composes `preset_keys`, `switches`, `keyboard_switch_policy` |
| `color` | file/operations | Composes `preset_color_schemes`, `fallback_colors` |
| `style` | file/operations | Composes `style`, `preedit`, `window`, `tool_bar`, `liquid_keyboard` |
| `resources` | operations | Composes `resources` |

### 2.3 Operation spec

For `keyboard`, `behavior`, `color`:

```yaml
keyboard:
  file: keyboards.yaml       # optional; loads preset_keyboards from the file
  # or
  files: [a.yaml, b.yaml]   # optional; loaded in order
  add:                      # new IDs
    new_id: { ... }
  override:                 # same-name replacement
    existing_id: { ... }
  remove:                   # drop inherited IDs
    - old_id
```

Rules:

- `add` target must not already exist.
- `override` target must already exist.
- `remove` target must already exist.
- `override` replaces the whole named entry (not deep-merge).
- For `style`, `override` is **field-level deep merge**:

```yaml
style:
  override:
    keyboard_height: 240
    key_height: 50
```

For `resources`, `add`/`remove` operate on a list of resource paths.

---

## 3. Section schemas

### 3.1 `preset_keyboards`

Same shape as today's `standard/keyboards.yaml` and theme `preset_keyboards`:

```yaml
preset_keyboards:
  default:
    name: 預設
    width: 10
    height: 44
    keys: [ ... ]
```

### 3.2 `preset_keys`

Same shape as today's `standard/preset_keys.yaml` and theme `preset_keys`:

```yaml
preset_keys:
  BackSpace: {label: 退格, repeatable: true, send: BackSpace}
  space: {repeatable: false, functional: false, send: space}
```

### 3.3 `switches` (new)

```yaml
switches:
  ascii_mode:
    states: [中, 英]
  ascii_punct:
    states: [。，, ．，]
  full_shape:
    states: [半角, 全角]
  simplification:
    states: [汉字, 汉字]
```

### 3.4 `keyboard_switch_policy` (new)

```yaml
keyboard_switch_policy:
  default_keyboard: default
  ascii_keyboard: letter
  aux:
    number: number
    symbols: symbols
    edit: edit
    func: func
  select:
    ".next": next
    ".last": last_lock
    ".default": default
    ".ascii": letter
```

This is the single source for `KeyboardSwitcher` behavior.

### 3.5 `colors` and `color_schemes`

Colors are a flat list of named palettes; schemes reference palettes by name:

```yaml
colors:
  A:
    back_color: 0xe4e7e9
    text_color: 0x5a676e
  B:
    back_color: 0x1e1e1e
    text_color: 0xe0e0e0
color_schemes:
  ColorA/B:
    light: A
    dark: B
  Single:
    light: A
```

Rules:

- `colors` values are full palettes using the existing color-key names.
- `color_schemes` values reference palette names; `dark` is optional and falls
  back to `light`.
- The UI displays the scheme/pair name, not raw palette contents.
- Legacy `preset_color_schemes` with inline `light:`/`dark:` palettes remains
  accepted for compatibility.

### 3.6 `fallback_colors`

Same shape as today's theme `fallback_colors`:

```yaml
fallback_colors:
  candidate_text_color: text_color
```

### 3.7 `style`, `preedit`, `window`, `tool_bar`, `liquid_keyboard`

Same shapes as today's theme fields.

### 3.8 `resources`

```yaml
resources:
  - backgrounds/14jian.png
  - fonts/custom.ttf
```

### 3.9 `rime_files` (schema packages only)

```yaml
rime_files:
  - rime/default.yaml
  - rime/cn_dicts/base.dict.yaml
  - rime/lua/select_character.lua
```

Entries are package paths under `rime/`. During schema-package install, each
file is copied to the Rime user data directory with the `rime/` prefix
removed, so the schema can be deployed with its dictionaries and Lua/OpenCC
dependencies present.

### 3.10 `theme_file` (schema packages only)

```yaml
theme_file: theme.yaml
```

An optional tier-2 decoration theme shipped with the package. When present, it
replaces the current decoration theme during schema-package install, so the
package can carry its own `style`, `fallback_colors`, `liquid_keyboard`,
`preedit`/`window`/`tool_bar`, and color-scheme overrides. The layout files
still overlay schema-specific preset keys/keyboards/colors on top of it.

---

## 4. Resolution algorithm

1. Start with empty sections:
   - `preset_keys = {}`
   - `preset_keyboards = {}`
   - `preset_color_schemes = {}`
   - `style = {}`
   - `preedit = {}`
   - `window = {}`
   - `tool_bar = {}`
   - `liquid_keyboard = {}`
   - `fallback_colors = {}`
   - `resources = []`
2. For each entry in `components` in order:
   - String path → load that component directory's files (must exist inside
     the packaged zip; no global `standard` magic).
   - `schema` → record schema file (not merged into Theme).
   - `keyboard` / `behavior` / `color` / `style` / `resources` → load file(s)
     then apply operations.
3. `add` / `override` / `remove` are validated immediately.
4. The final sections are the resolved component definition.

The resolved output maps to the existing runtime `Theme` object unchanged.

---

## 5. Validation rules

| # | Rule |
|---|---|
| 1 | `components` must be a non-empty list |
| 2 | Every component string must resolve to an existing directory inside the package |
| 3 | `add` IDs must not already exist |
| 4 | `override` IDs must already exist |
| 5 | `remove` IDs must already exist |
| 6 | No component include cycles (for nested `include` if added later) |
| 7 | Every keyboard reference in switching policy must exist after resolution |
| 8 | Every `preset_keys` reference from keyboard keys must exist after resolution |
| 9 | Every `fallback_colors` reference must exist in a color scheme |
| 10 | Every resource path in `resources` must exist in the package |
| 11 | `schema_file` must exist and be a valid Rime schema |
| 12 | `default_keyboard` must exist after resolution |

---

## 6. Example: tongwenfeng as a component manifest

```yaml
# sample_theme_schemas/tongwenfeng/manifest.yaml
name: tongwenfeng
author: 风花絮
version: "1.0"

use_standard_preset_keys: true
standard_keyboards: [default, letter, number, symbols]
standard_color_schemes: [default]

components:
  - standard
  - shared-aux
  - style:
      file: style.yaml      # style + liquid keyboard
  - color:
      file: color.yaml      # color schemes + fallback colors
  - style:
      file: chrome.yaml     # preedit / window / tool_bar
```

The `shared-aux` directory is the clean tongwenfeng base (Option A):

- `keyboard.yaml` — tongwenfeng helper keyboards
- `behavior.yaml` — tongwenfeng preset keys (standard duplicates dropped)

Style/color/liquid/chrome are theme-specific and live in the theme's own files.

Resolving this manifest reproduces the tongwenfeng definition set without
copying anything into the manifest itself.

## 6.1 Example: 简纯+14键 as a thin component manifest

```yaml
# sample_theme_schemas/简纯+14键/component.yaml
name: 简纯+14键
author: amzxyz
version: "1.0"

components:
  - standard
  - shared-aux
  - schema:
      file: 14jian.schema.yaml
  - keyboard:
      file: keyboard.yaml      # only same-name keyboard overrides
  - keyboard:
      file: 14jian.layout.yaml # schema-specific keyboards
  - behavior:
      file: behavior.yaml      # only same-name preset-key overrides
  - color:
      file: color.yaml         # theme color schemes + fallback colors
  - style:
      file: style.yaml         # theme style + liquid keyboard
```

The generated delta files are intentionally small:

- `keyboard.yaml` — 4 same-name keyboard overrides + 59 new helper keyboards
  (`sym*`, `emoji*`, `ywz*`, `edit`, `func`, `symbolscn`)
- `component.yaml` also removes the 46 tongwenfeng-only helper keyboards not
  used by 简纯+14键
- `behavior.yaml` — same-name preset-key overrides + new preset keys; removes
  tongwenfeng-only keys not used by this theme
- `color.yaml` — the 4 theme color schemes + fallback colors
- `style.yaml` — theme style + liquid keyboard
