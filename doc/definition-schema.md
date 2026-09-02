# Trime Definition Schema (self-contained IME packages)

This document defines the YAML shapes for **self-contained input-method
packages**. Each package is a zip that contains all required definitions:
keyboards, behaviors, colors, style/chrome, Rime schemas, and resources.
Packages do **not** inherit from or override an app-shipped global standard
catalog. Overriding is allowed only within the package.

The app-shipped default IME package (tongwenfeng + built-in Rime schemas such
as `luna_*`) is itself a self-contained package delivered through the same
package path as customer packages.

---

## Package layout

A package is a zip (or an on-disk package directory) with this shape:

```text
manifest.yaml              # package identity + component composition
keyboard.yaml              # preset_keyboards
behavior.yaml              # preset_keys / switches / switching policy
color.yaml                 # preset_color_schemes + fallback_colors
style.yaml                 # style / preedit / window / tool_bar / liquid_keyboard
chrome.yaml                # optional chrome split (or folded into style.yaml)
rime/                      # Rime schema files, dictionaries, Lua, OpenCC, ...
resources/                 # backgrounds, fonts, sounds, images (optional)
components/                # optional local components referenced by the manifest
```

Component files may also live in sibling directories outside the package
source tree; the packaging tool must copy them into the zip so the installed
package is self-contained.

## `manifest.yaml`

A component manifest drives the package composition:

```yaml
name: 简纯+14键
author: amzxyz
version: "1.0"

schema_id: 14jian
default_keyboard: 14jian

components:
  - schema:
      file: rime/14jian.schema.yaml
  - keyboard:
      file: keyboard.yaml
  - behavior:
      file: behavior.yaml
  - color:
      file: color.yaml
  - style:
      file: style.yaml
```

Rules:

- Every component entry must resolve inside the package (after packaging).
- There is no global or magic `standard` reference; string component entries
  must name local directories that are included in the package.
- Later components override earlier ones. `add` / `override` / `remove` are
  validated within the composed package only.

## Split YAML files

### `keyboard.yaml`

```yaml
preset_keyboards:
  default:
    name: 預設
    width: 10
    height: 44
    keys: [...]
  14jian:
    name: 14键
    keys: [...]
```

`__include` remains the only inheritance mechanism and must resolve inside the
package.

### `behavior.yaml`

```yaml
preset_keys:
  BackSpace: {label: 退格, repeatable: true, send: BackSpace}
  space: {repeatable: false, functional: false, send: space}
  Mode_switch:
    command: mode_switch
    option: ascii_mode
    states: [中, 英]
```

### `color.yaml`

Colors are a **flat list of named palettes**, and color schemes are thin pairs
that reference palettes by name:

```yaml
colors:
  A:
    name: 浅色
    back_color: 0xe4e7e9
    text_color: 0x5a676e
    key_back_color: 0xfbfbfc
    ...
  B:
    name: 深色
    back_color: 0x1e1e1e
    text_color: 0xe0e0e0
    key_back_color: 0x263238
    ...
color_schemes:
  ColorA/B:
    name: 浅色 / 深色
    light: A
    dark: B
  Single:
    light: A
fallback_colors:
  candidate_text_color: text_color
```

Rules:

- `colors` is a flat map of palette names → full color-key definitions.
- `color_schemes` entries reference palette names via `light` / `dark`.
- If `dark` is omitted, both light and dark use the `light` palette.
- The UI shows the color **pair name** (e.g. `ColorA/B`), not the palette
  contents.
- The legacy `preset_color_schemes` shape (inline `light:`/`dark:` palettes)
  is still accepted for compatibility, but new resources should use the flat
  `colors` + `color_schemes` shape.
- Color values use **Android's order**: `0xRRGGBB` for opaque colors and
  `0xAARRGGBB` (alpha **first**) for 8-digit colors. `0x80141617` is black at
  50% alpha; writing the alpha last (`0x14161780`, CSS style) would be parsed
  as a different color. Named colors (`red`, `blue`, …) are also accepted.

### `style.yaml` / `chrome.yaml`

`style.yaml` owns `style`, `preedit`, `window`, `tool_bar`, and optionally
`liquid_keyboard`. `chrome.yaml` may hold the `preedit` / `window` /
`tool_bar` sections separately; the resolver merges them.

### `<schemaId>.extended.yaml` (per-schema extension files)

Each schema may ship a sibling application-level extension file, named after
the Rime schema id and stored under `rime/` (the prefix is stripped at install
time, so it lands in the workspace root):

```text
rime/wanxiang_t9.extended.yaml
rime/wanxiang_flypy_t9.extended.yaml
```

The Rime engine **never** reads these files — they are app-owned directives.
Two sections are defined:

```yaml
# wanxiang_t9.extended.yaml
schema_id: wanxiang_t9        # optional; binds the file to a schema (validated)

tool_bar:                     # per-schema toolbar override (Feature ①)
  __replace: false            # false = deep-merge onto chrome.yaml's tool_bar
                              # true  = replace it entirely
  button_spacing: 6
  primary_button: { ... }     # same shape as chrome.yaml tool_bar
  buttons: [ ... ]

t9_disambiguation:            # T9 pinyin disambiguation column (Feature ②)
  enabled: true               # default on for t9=true schemas
  input_method: full          # full | flypy
  syllables:                  # standard pinyin syllable table (no tone)
    - {pinyin: hao, t9_code: '426', flypy_code: hc, flypy_t9_code: '42'}
    - ...
  flypy_keys:                 # 小鹤双拼 key mapping (flypy only)
    initials: { zh: v, ch: i, sh: u, ... }
    finals:   { ao: c, ai: d, ... }
```

Rules:

- The file must declare at least one of `schema_id` / `tool_bar` /
  `t9_disambiguation`.
- `tool_bar.__replace` must be a boolean; the default is `false` (deep merge,
  matching the component resolver's `mergeMappings` semantics for scalar/map
  sections — an explicit leaf in the extension wins, absent leaves keep the
  package value).
- `t9_disambiguation.input_method` must be `full` or `flypy`.
- Every `syllables` entry needs a `pinyin` and a `t9_code`; the code must
  equal the deterministic T9 fold of the pinyin (`A-Z → 222333444…`), and a
  present `flypy_code` must decode via the `flypy_keys` tables.
- The syllable table is generated by `script/generate_pinyin_syllables.py`;
  `script/extended_validator.py` (wired into `validate-definitions.py`)
  checks consistency.

## Rime files

Rime schema files, dictionaries, Lua scripts, OpenCC data, `default.yaml`,
`symbols_*.yaml`, etc. are stored under `rime/` inside the package. During
install the app copies them into the Rime user data directory (the `rime/`
prefix is stripped) before deploying the schemas.

## Default app package

The app-provided default IME package is assembled from:

- the split tongwenfeng components (`keyboard.yaml`, `behavior.yaml`,
  `style.yaml`, `color.yaml`, `chrome.yaml`, `liquid_keyboard.yaml`), and
- the built-in Rime schemas from `app/src/main/assets/shared/` (`luna_*`,
  `stroke`, `pinyin`, etc.).

It is shipped as a self-contained package and loaded through the same path as
customer packages.

## Canonical IME workflow (app-managed IME library)

The IME library lives in the app-managed Rime user data directory
(`getExternalFilesDir(null)/rime`, e.g.
`Android/data/<package>/files/rime`). It requires no broad storage permission:
the app owns the whole Rime data area. The `IMEs/` subdirectory is the
persistent package library: it survives installation, uninstallation, and app
restarts, and it holds the available package zips plus the active package
manifest.

### First install / activation

1. User selects a package zip from the app-managed IME library (normally
   `Default.zip`).
2. The app extracts the archive into the Rime user data directory, resolves the
   package component manifest into a complete `Theme` (all definitions come
   from inside the package), and copies package resources into the user
   backgrounds directory.
3. The app copies package `rime_files` into the Rime user data directory,
   deploys auxiliary schemas first and then the main schema(s), adds the
   schema to `default.custom.yaml` as the default input method, and
   activates/switches to it.
4. The active package manifest is stored in
   `IMEs/active-manifest.yaml` for later uninstall/switch.

### Switching to another package

1. Read the active manifest.
2. Delete the files it lists as package-installed files from the Rime user data
   directory.
3. Clear the `build/` subdirectory (compiled artifacts are derived, not user
   data).
4. Extract the new package into the Rime user data directory.
5. Run the Rime deploy/compile.
6. Update `IMEs/active-manifest.yaml`.

User/generated data — user dictionaries, custom phrase files, `user.yaml`,
logs, installation metadata — is never deleted by a switch. Files not listed
in the active manifest are preserved.

## Validation summary

- Package manifest must exist and have valid component entries.
- All referenced components/files must exist inside the packaged zip.
- Unknown local component paths or `__include` targets → error.
- Unsafe zip paths → error.
- Empty keyboard/behavior/color/style sections → error only when required by the package.
- `rime_files` entries must be strings under `rime/` → error.
- Color scheme `dark:` may only contain known color keys.
- `<schemaId>.extended.yaml` files must declare at least one of `schema_id` /
  `tool_bar` / `t9_disambiguation`; `__replace` must be boolean; syllable
  codes must be consistent with their pinyin (see the extended-file section
  above).
