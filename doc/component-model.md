# Component Model for Trime Definitions

Status: **proposed design** (brainstormed 2026-08-16)

This document replaces the mental model of "one theme = one monolithic YAML"
with a **component composition model**. The goal is to make a complete input
method definition a small manifest that reuses shared components and overrides
only what is different.

The design is intentionally incremental: the runtime can keep producing the
same `Theme` object we already have; only the authoring/validation layer
changes first.

---

## 1. Why we need this

Today `tongwenfeng.trime.yaml` and `简纯+14键.trime.yaml` are both large
monolithic files that carry their own copies of:

- standard/helper keyboards (`default`, `letter`, `number`, `symbols`, aux
  symbol/emoji/颜文字 pages)
- preset key behaviors / macros / switch actions
- color schemes
- style / sizes / spacing / liquid keyboard
- schema-specific keyboards (in the 14键 case)

A user who wants a new input method based on the same skin must copy the whole
monolith and edit it. There is no proper overriding mechanism, so fixes and
improvements do not propagate between themes.

The desired model:

> A complete input method definition is a **composition of small named
> components**, each owning one concern. A concrete theme/package declares what
> it includes, adds, overrides, or removes.

---

## 2. Component types

| Component | Owns | Example file |
|---|---|---|
| `schema` | Rime engine configuration | `14jian.schema.yaml` |
| `keyboard` | main + auxiliary keyboard layouts | `keyboards/*.yaml` |
| `behavior` | preset keys, shortcuts, macros, switch actions, keyboard-switching policy | `behavior.yaml` |
| `style` | sizes, gaps, fonts, chrome, `generalStyle`, liquid keyboard | `style.yaml` |
| `color` | color schemes + fallback tables | `color.yaml` |
| `resources` | fonts, backgrounds, sounds, images | `resources/` |
| `shared-aux` | reusable helper keyboards/behaviors/liquid shared by multiple themes | `shared-aux/` |
| `manifest` | identity, dependencies, composition order, overrides | `manifest.yaml` |

The exact file names are a convention; what matters is that each concern can be
**addressed, reused, and overridden independently**.

---

## 3. Component manifest

A concrete input method (theme + schema package) is described by a manifest:

```yaml
# sample_theme_schemas/简纯+14键/manifest.yaml (target shape)
name: 简纯+14键
author: amzxyz
version: "1.0"

schema_id: 14jian
default_keyboard: 14jian

components:
  - standard                 # app-shipped standard catalog
  - shared-aux               # tongwenfeng-derived shared helper keyboards/behaviors
  - schema:
      file: 14jian.schema.yaml
  - keyboard:
      add:
        - 14jian
        - letter_14jian
        - 14number
        - 14numberen
        - 14symbols
        - 14symbolsen
        - letter_18jian
  - behavior:
      add:
        - 14keyqw
        - 14keyer
        - 14Keyboard_number
        - Back14
      override:
        - Keyboard_symbols
      remove: []
  - color:
      override:
        default:
          light: { ... }
          dark: { ... }
      add:
        google_white: { ... }
        google_black: { ... }
  - style:
      override:
        keyboard_height: 240
        key_height: 50
        keyboard_padding_right: 40
```

### 3.1 Composition order

Later components override earlier ones. The canonical order is:

1. `standard`
2. `shared-aux`
3. theme components (`style`, `color`, `behavior`, `keyboard`)
4. schema-package components (`schema`, `keyboard`, `behavior`, `color`, `resources`)

For a schema package installed on top of an active theme, the schema package
is the last layer and wins.

---

## 4. Override semantics

All components are addressable by stable IDs:

- keyboards: `default`, `letter`, `number`, `symbols`, `14jian`, ...
- preset keys / behaviors: `BackSpace`, `space`, `14keyqw`, `Keyboard_14jian`, ...
- color schemes: `default`, `dark_temple`, `google_white`, ...
- style fields: `keyboard_height`, `key_height`, `horizontal_gap`, ...
- resources: `fonts/latin.ttf`, `backgrounds/14jian.png`, ...

The only four operations are:

| Operation | Meaning | Validation |
|---|---|---|
| `include` | reuse another component | target component must exist |
| `add` | introduce new IDs | ID must not already exist in the current composed set |
| `override` | same-name replacement | ID must already exist in the composed set |
| `remove` | explicitly drop an inherited ID | ID must already exist in the composed set |

Unknown targets fail validation. This prevents silent copy/paste drift.

### 4.1 Example

```yaml
components:
  - standard
  - shared-aux
  - keyboard:
      add: [14jian, letter_14jian]
      override:
        default:
          keys:
            - ...   # only the delta you want
      remove: [old_unused_page]
```

---

## 5. Shared-aux component

`shared-aux` is the reusable "tongwenfeng base" for common helper keyboards,
behaviors, liquid keyboard, and style fragments.

Decision (2026-08-16):

- Use **tongwenfeng** as the source/base for shared keyboards.
- `shared-aux` should contain most existing helper keyboards:
  - symbol pages
  - emoji pages
  - 颜文字 pages
  - liquid keyboard
  - common preset key behaviors/macros
  - common style/layout parameters

Open question: whether to split `shared-aux` into smaller opt-in packs
(`symbols-cn`, `emoji`, `ywz`, `liquid`, `behaviors`) or keep one larger
component. Initial direction: one `shared-aux` component first, then split if
needed.

---

## 6. Behavior component

`behavior.yaml` owns:

- `preset_keys` (keypress → action/macro/shortcut)
- switch definitions (ascii_mode, full_shape, simplification, ascii_punct,
  extended_charset, ...)
- keyboard-switching policy:
  - default keyboard per schema
  - ascii companion keyboard
  - aux pages (number / symbols / emoji / edit / func)
  - `.next`, `.last`, `.default`, `.ascii` behavior

This removes the current scattering across:

- Rime schema `switches`
- `preset_keys` (`Mode_switch`, `Punct_switch`, ...)
- `style.keyboards`
- `KeyboardSwitcher` policy code

The runtime `KeyboardSwitcher` should eventually consume the resolved behavior
component instead of hard-coded heuristics.

---

## 7. Style and color components

### Style

`style.yaml` owns `generalStyle` fields, chrome (`window`, `preedit`,
`tool_bar`), and optionally `liquid_keyboard`.

Overrides are field-level:

```yaml
style:
  override:
    keyboard_height: 240
    key_height: 50
    keyboard_padding_right: 40
```

### Color

`color.yaml` owns `preset_color_schemes` and `fallback_colors`.

Colors are referenced by name:

- If a color scheme already exists in a base component, use `override`.
- If it is new, use `add`.
- If a schema package needs colors that only exist for that schema, the
  package may ship its own `color.yaml`; package colors are the last layer.

Open question: whether a theme's `color.yaml` should be a companion file to
`style.yaml` (requiring a small loader change) or a layout fragment listed in
the package manifest. The component model treats them as separate components,
so the runtime loader will need to merge both.

---

## 8. Resources

Each component may reference resources:

```yaml
resources:
  - backgrounds/14jian.png
  - backgrounds/14jian.night.png
  - fonts/custom.ttf
```

Rules:

- Every resource listed in `manifest.resources` must exist in the package.
- Every font/background referenced by style/colors must be either a standard
  resource or declared in the component/package.
- Resource override follows the same layering order as other components.

---

## 9. Validation rules

The component resolver must validate:

1. Manifest YAML shape.
2. Component dependencies exist.
3. `add` targets do not already exist.
4. `override` / `remove` targets already exist.
5. No include cycles.
6. Every keyboard references known preset keys/behaviors.
7. Every keyboard referenced by switching policy exists.
8. Every color referenced by `fallback_colors` exists in a scheme.
9. Every resource reference exists.
10. Schema file exists and is a valid Rime schema.
11. `default_keyboard` exists in the composed keyboard set.

This goes beyond the current structural validator.

---

## 10. Runtime mapping (backward compatible)

The component resolver produces the same merged `Theme` shape used today:

- resolved `preset_keys` → `Theme.presetKeys`
- resolved `preset_keyboards` → `Theme.presetKeyboards`
- resolved `preset_color_schemes` → `Theme.colorSchemes`
- resolved `style` / `fallback_colors` / `liquid_keyboard` → `Theme` fields
- schema package → `SchemaLayoutPackage` installed/deployed as today

The runtime does not need to know about components; it still sees a fully
merged definition. This lets us introduce the authoring/validation layer
without a risky runtime rewrite.

---

## 11. Migration plan

1. Write this design doc (done once this file lands).
2. Define the component manifest schema precisely (`doc/component-schema.md`
   or extend `doc/trime-schema.json`).
3. Implement a pure component resolver + unit tests:
   - layering order
   - add/override/remove/include
   - cycle detection
   - unknown-target errors
4. Extract `shared-aux` from `tongwenfeng.trime.yaml`:
   - identify canonical keyboard/key/behavior/style IDs
   - create `shared-aux/` component files
   - make `tongwenfeng` a thin manifest over `shared-aux`
5. Convert `简纯+14键` to:
   - `include: [standard, shared-aux]`
   - schema package (`14jian.schema.yaml`, `14jian.layout.yaml`)
   - color/style/behavior deltas only
6. Keep legacy monolithic files loadable during transition.
7. Extend `script/validate-definitions.py` to validate component manifests.
8. Update `doc/definition-schema.md` and `doc/trime-schema.json`.
9. Migrate the in-app validator and schema-package installer to use the
   component resolver.

---

## 12. Open questions

- Should `shared-aux` be one component or several opt-in packs?
- Should `liquid_keyboard` live in `style` or its own component?
- Should switch definitions be part of `behavior` or a separate `switches`
  component?
- How should component manifests be delivered in a schema-layout zip?
  (`manifest.yaml` at package root is the current convention.)
- How much backward compatibility must the old monolithic files retain?
- Does the in-app theme picker need to understand components, or only the
  resolved theme?

---

## 13. Relationship to existing files

| Existing | Role after migration |
|---|---|
| `app/src/main/assets/shared/standard/*` | `standard` component (already exists) |
| `app/src/main/assets/shared/tongwenfeng.trime.yaml` | source for `shared-aux`; eventually a thin manifest |
| `sample_theme_schemas/简纯+14键/` | first real consumer of `standard` + `shared-aux` + schema package |
| `script/split_legacy_theme.py` | temporary conversion tool; superseded by component migration |
| `script/validate-definitions.py` | extended to validate component manifests |
| `doc/definition-schema.md` | updated to describe component files |
