# Component Model for Trime Definitions

Status: **accepted package model** (revised 2026-08-17)

> Historical design document. The implemented model is the self-contained
> flat IME package described in `doc/definition-schema.md` and
> `doc/refactor-handoff.md`. Later sections that mention `standard/`,
> `shared-aux/`, or schema-layout packages are preserved for history and no
> longer match the current code.

This document replaces the mental model of "one theme = one monolithic YAML"
with a **self-contained component package model**. A complete input method is
a zip that contains all required YAML definitions (keyboards, colors, style,
theme/chrome, Rime schemas, resources) and composes them locally. Packages do
not inherit from an app-shipped global "standard" catalog.

The design is intentionally incremental: the runtime can keep producing the
same `Theme` object we already have; only the authoring/validation/package
layer changes first.

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

> A complete input method definition is a **self-contained package**: a zip
> containing a composition of small named components, each owning one concern.
> A package declares what it includes, adds, overrides, or removes **inside the
> package**. It does not override or inherit from an app-shipped standard
> catalog.

### 1.5 Self-contained packages

- Every input-method package contains all YAML definitions it needs: keyboards,
  behaviors, colors, style/chrome, theme data, Rime schemas, and resources.
- Reuse is achieved by including local components inside the package (or by
  copying them into the package), not by referencing a global standard layer.
- Overriding is allowed only within the package.
- The app-shipped default IME package (tongwenfeng + the built-in Rime schemas
  such as `luna_*`) is itself a self-contained package delivered through the
  same package path as customer packages.
- Legacy monolithic `*.trime.yaml` files remain loadable during the transition,
  but new packages are expected to use the self-contained component shape.

### 1.6 IME package lifecycle (app-managed Rime data)

IME selection is package selection, not theme selection:

- The Rime user data directory is app-managed
  (`getExternalFilesDir(null)/rime`), so no broad storage permission is
  needed. Its `IMEs/` subdirectory is a **persistent** folder that survives
  install/uninstall and app restarts and holds the available package zips and
  manifests.
- Initially the managed Rime data directory is empty. The first selected
  package (normally `Default.zip`) is extracted into it and compiled by Rime.
- The active package's manifest is kept in `IMEs/` (e.g.
  `active-manifest.yaml`) and doubles as the **uninstall manifest**.
- Switching to another package:
  1. Read the active manifest.
  2. Delete the files it lists as package-installed files from the Rime user
     data directory.
  3. Clear the `build/` subdirectory (compiled artifacts are derived, not user
     data).
  4. Extract the new package into the Rime user data directory.
  5. Run the Rime deploy/compile.
- User/generated data is never deleted on switch:
  - user dictionaries (`*.userdb`, userdb files)
  - custom phrase files
  - `user.yaml`
  - logs / installation metadata
  - any file not listed in the active manifest
- `IMEs/` remains untouched by extraction/deletion, so packages and manifests
  survive switches.

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
# sample_theme_schemas/简纯+14键/component.yaml (self-contained package shape)
name: 简纯+14键
author: amzxyz
version: "1.0"

schema_id: 14jian
default_keyboard: 14jian

components:
  - standard               # local sibling component, included in the package zip
  - shared-aux             # local sibling component, included in the package zip
  - schema:
      file: 14jian.schema.yaml
  - keyboard:
      file: keyboard.yaml
      add:
        - 14jian
        - letter_14jian
        - 14number
        - 14numberen
        - 14symbols
        - 14symbolsen
        - letter_18jian
  - behavior:
      file: behavior.yaml
      add:
        - 14keyqw
        - 14keyer
        - 14Keyboard_number
        - Back14
      override:
        - Keyboard_symbols
      remove: []
  - color:
      file: color.yaml
      override:
        default:
          light: { ... }
          dark: { ... }
      add:
        google_white: { ... }
        google_black: { ... }
  - style:
      file: style.yaml
      override:
        keyboard_height: 240
        key_height: 50
        keyboard_padding_right: 40
```

### 3.1 Composition order

Later components override earlier ones. Within a self-contained package the
order is:

1. local base components (`standard`, `shared-aux`, or any package-local component)
2. theme components (`style`, `color`, `behavior`, `keyboard`)
3. package-specific components (`schema`, `keyboard`, `behavior`, `color`, `resources`)

All referenced components are packed into the zip, so the installed package
never needs to reach outside itself for definitions.

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

`shared-aux` is the reusable "tongwenfeng base" for common helper keyboards and
behaviors.

Decision (2026-08-16, Option A):

- Use **tongwenfeng** as the source/base for shared keyboards.
- `shared-aux` contains:
  - `keyboard.yaml` — tongwenfeng helper keyboards
  - `behavior.yaml` — common preset key behaviors/macros (standard duplicates
    dropped)
- Style, color, liquid, and chrome are **theme-specific** and live in each
  theme's own `style.yaml` / `color.yaml` / `chrome.yaml`.

Open question: whether to split `shared-aux` into smaller opt-in packs
(`symbols-cn`, `emoji`, `ywz`, `behaviors`) or keep one larger component.
Initial direction: one `shared-aux` component first, then split if needed.

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

`color.yaml` owns a flat list of named palettes plus thin scheme pairs:

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
```

- `colors` defines complete palettes using the existing color-key names.
- `color_schemes` entries reference palette names; missing `dark` falls back
  to `light`.
- The UI shows the scheme/pair name, not the raw palette contents.
- Legacy `preset_color_schemes` with inline `light:`/`dark:` palettes remains
  accepted for compatibility.

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
merged definition. `ComponentThemeLoader` now implements this bridge:
`ThemeFilesManager` discovers component manifests and `ThemeManager` loads them
through the resolver before falling back to monolithic theme files.

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

## 12. Reference prototype

A pure-Python prototype exists to validate the composition semantics:

- `script/component_resolver.py` — resolves a component manifest into merged
  `preset_keys` / `preset_keyboards` / `preset_color_schemes` / `style` /
  `liquid_keyboard` / `fallback_colors` / `resources` sections.
- `script/test_component_resolver.py` — unit tests for layering order and
  `add` / `override` / `remove` validation.

Run:

```bash
cd script
python3 -m unittest test_component_resolver -v
```

The Kotlin port now exists under
`app/src/main/java/com/osfans/trime/data/theme/component/`:

- `ComponentManifest.kt` — manifest/spec parser
- `ComponentSource.kt` — file/ in-memory source abstraction
- `ComponentResolver.kt` — pure resolver producing merged `Node.Mapping`
  sections

Tests: `app/src/test/java/com/osfans/trime/data/theme/component/ComponentResolverTest.kt`
and `BehaviorVerifierTest.kt`.

`BehaviorVerifier.kt` walks every keyboard action (`click`, `long_click`,
`swipe_*`, `composing`) and validates preset-key references, `select:` targets,
toggle states, and keyboard-level references.

Run:

```bash
GRADLE_USER_HOME=$PWD/.gradle-test-home ./gradlew :app:testDebugUnitTest \
  --tests "com.osfans.trime.data.theme.component.*" --offline
```

A first real component extraction exists:

- `sample_theme_schemas/shared-aux/` — component files extracted from
  `tongwenfeng.trime.yaml` plus the normalized helper keyboards/behaviors from
  `简纯+14键` (`keyboard.yaml`, `behavior.yaml`, `style.yaml`, `color.yaml`).
- `sample_theme_schemas/standard/` — local copy of the standard keyboards /
  preset keys / colors, so component manifests can reference it as a local
  sibling component instead of an app-shipped global.
- `sample_theme_schemas/tongwenfeng/manifest.yaml` — a component manifest that
  reproduces tongwenfeng as `standard` + `shared-aux` + `chrome.yaml`;
  this is the app-provided default IME package once Rime schemas are added.
- `sample_theme_schemas/简纯+14键/component.yaml` — a component manifest that
  composes `standard` + `shared-aux` + schema package files and only
  overrides the same-name keyboards/behaviors/colors/style/liquid that differ
  from the shared base.

Resolve it with:

```bash
python3 - <<'PY'
import sys
sys.path.insert(0, 'script')
from component_resolver import resolve_file
sections = resolve_file(__import__('pathlib').Path('sample_theme_schemas/tongwenfeng/manifest.yaml'))
print(len(sections['preset_keys']), len(sections['preset_keyboards']))
PY
```

---

## 13. Open questions

- Should `shared-aux` be one component or several opt-in packs?
- How to unify helper keyboard IDs between tongwenfeng (`bq*`, `kao_*`,
  `fbj*`) and 简纯+14键 (`sym*`, `emoji*`, `ywz*`) so 简纯+14键 can consume
  `shared-aux` without carrying its own near-duplicate pages?
- Should `liquid_keyboard` live in `style` or its own component?
- Should switch definitions be part of `behavior` or a separate `switches`
  component?
- How should component manifests be delivered in a schema-layout zip?
  (`manifest.yaml` at package root is the current convention.)
- How much backward compatibility must the old monolithic files retain?
- Does the in-app theme picker need to understand components, or only the
  resolved theme?

---

## 14. Relationship to existing files

| Existing | Role after migration |
|---|---|
| `app/src/main/assets/shared/standard/*` | source for the local `sample_theme_schemas/standard/` component; not a global inheritance layer |
| `app/src/main/assets/shared/tongwenfeng.trime.yaml` | source for `shared-aux`; the default package is a self-contained component manifest |
| `sample_theme_schemas/简纯+14键/` | first real consumer of `standard` + `shared-aux` + schema package |
| `script/split_legacy_theme.py` | temporary conversion tool; superseded by component migration |
| `script/validate-definitions.py` | extended to validate component manifests |
| `doc/definition-schema.md` | updated to describe component files |
| `doc/component-schema.md` | precise component manifest/file schema (draft) |
