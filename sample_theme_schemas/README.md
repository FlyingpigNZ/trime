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

The app-provided default package source lives in
`app/src/main/assets/shared/Default/`, not under `sample_theme_schemas/`.
It is a self-contained split package with `manifest.yaml`, `keyboard.yaml`,
`behavior.yaml`, `color.yaml`, `style.yaml`, `chrome.yaml`,
`liquid_keyboard.yaml`, and its own `rime/` files.

Build `Default.zip`:

```bash
python3 script/build_default_package.py
```

The script requires the Rime data submodules to be checked out (the shared
asset symlinks must resolve).

## Customized package

`简纯+14键.zip` is a self-contained IME package. It is the shipping artifact and
already contains everything needed on import:

- `manifest.yaml` — component manifest with `components`
- `keyboard.yaml` / `behavior.yaml` / `color.yaml` / `style.yaml` /
  `chrome.yaml` / `liquid_keyboard.yaml`
- Rime schemas: `14jian.schema.yaml`, `double_pinyin_flypy.schema.yaml`,
  `rime_ice.schema.yaml`
- optional `rime/` (Rime files required by the schemas)
- optional `backgrounds/` (keyboard background images)

There is no unpacked source directory checked in; the zip itself is the source
of truth. To modify the package, unzip it, edit the files, and re-zip, or keep
a local source copy outside the repo.

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
