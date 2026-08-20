# Sample IME packages for the self-contained package model

This directory contains sample input-method packages. The model is:

- Each package is a **self-contained zip**: it carries all required YAML
  definitions (keyboards, behaviors, colors, style/chrome, Rime schemas,
  resources).
- Packages do **not** inherit from or override an app-shipped global standard
  catalog. Reuse is done by including local components inside the package.
- Overriding is allowed only within the package.
- The app-provided default package (tongwenfeng + built-in Rime schemas) is
  itself a self-contained package.

## Package layout

Packages are flat: all definition YAMLs live at the package root, with no
`base/`, `standard/`, or `shared-aux/` subfolders needed inside a package.

## Default package

`tongwenfeng/` is the split default IME package:

- `manifest.yaml` — component manifest (`keyboard.yaml`, `behavior.yaml`,
  `color.yaml`, `style.yaml`, `liquid_keyboard.yaml`, `chrome.yaml`,
  `rime_files`)
- `style.yaml` / `liquid_keyboard.yaml` / `color.yaml` / `chrome.yaml` —
  theme-specific definition blocks
- `rime_files` — built-in Rime schemas/resources (`luna_*`, `stroke`,
  `pinyin`, prelude files) pulled from `app/src/main/assets/shared/`

Build `Default.zip`:

```bash
python3 script/build_default_package.py
```

The script requires the Rime data submodules to be checked out (the shared
asset symlinks must resolve).

## Customized package

`简纯+14键/` is a self-contained IME package:

- `manifest.yaml` — the single component manifest referencing root
  `keyboard.yaml`, `behavior.yaml`, `color.yaml`, `style.yaml`,
  `liquid_keyboard.yaml`, and schema files
- `keyboard.yaml` / `behavior.yaml` / `color.yaml` / `style.yaml` /
  `liquid_keyboard.yaml` — direct definition splits
- `14jian.schema.yaml` — full Rime schema
- `rime_files` — Rime resources pulled from the sibling `rime.雾凇/` folder
  when packaging

## Shipping boundary

- **App-shipped default package**: tongwenfeng split components + built-in
  Rime schemas, delivered as a self-contained package in app assets.
- **Customer-defined packages** (e.g. 简纯+14键 / 14jian) are delivered as a
  **zip** containing:
  - `manifest.yaml` (component manifest with `components`)
  - split YAML definition files (`keyboard.yaml`, `behavior.yaml`,
    `color.yaml`, `style.yaml`, `chrome.yaml`, `liquid_keyboard.yaml`)
  - `14jian.schema.yaml`
  - optional `rime/` (Rime files required by the schema)
  - optional `backgrounds/` (keyboard background images) or `resources/`
    (future fonts/backgrounds packaging)

Generate the 14键 package zip locally:

```bash
python3 script/package_schema.py sample_theme_schemas/简纯+14键
```
