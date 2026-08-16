# Component Validation Gaps

This document identifies what is missing before we can claim that a component
package is **proved correct** at input time.

## 1. Current state

| Area | What exists | Gap |
|---|---|---|
| Component manifest schema | `doc/component-schema.json` | Only covers `manifest.yaml`; no schemas for component files |
| `keyboard.yaml` schema | None | No machine-checkable schema |
| `behavior.yaml` schema | None | No schema for preset keys / switches / policy |
| `style.yaml` schema | None | No schema for style/chrome/liquid |
| `color.yaml` schema | None | No schema for color schemes/fallbacks |
| `resources.yaml` schema | None | No schema for resource lists |
| Schema-layout package manifest | Partial in `doc/trime-schema.json` | Not unified with component validation |
| Structural validator | `DefinitionValidator` + CLI | Partial; CLI lacks color checks |
| Semantic validator | `ComponentResolver` add/override/remove checks | Not exposed through a single validation entry point |
| Cross-reference validator | `BehaviorVerifier` | Separate, not integrated into main validation |
| Color literal validator | `DefinitionValidator.validateColorLiterals` | Only wired into component theme load; not CLI/in-app/install |
| In-app validator | Profile settings | Now validates component manifests when a local file path is available |
| Theme load | `ComponentThemeLoader` | Now runs full `ComponentValidator` before returning `Theme` |
| Package install | `SchemaLayoutPackageInstaller` | Now validates referenced files and layout YAML before unpacking |

## 2. What a complete schema must cover

### 2.1 Component manifest (`manifest.yaml` / `component.yaml`)

- `name`, `author`, `version`
- `use_standard_preset_keys`, `standard_keyboards`, `standard_color_schemes`
- `schema_id`, `schema_file`, `layout_files`, `default_keyboard`, `resources`
- `components` list with `include` / file / `add` / `override` / `remove`

Already partially defined in `doc/component-schema.json`.

### 2.2 `keyboard.yaml`

- `preset_keyboards` mapping
- Each keyboard:
  - required: `name` (string), or `__include` (string)
  - optional: `author`, `width`, `height`, `keyboard_height`, gaps, round corner, ascii mode, lock, `ascii_keyboard`, `landscape_keyboard`, `keys`
  - `keys` is a list of key mappings
  - Each key:
    - at least one behavior field: `click`, `long_click`, `swipe_up`, `swipe_down`, `swipe_left`, `swipe_right`, `composing`
    - optional style fields: `width`, `height`, `label`, `hint`, colors, offsets

### 2.3 `behavior.yaml`

- `preset_keys` mapping
- Each preset key:
  - at least one of: `send`, `text`, `commit`, `command`, `toggle`, `select`
  - optional: `label`, `preview`, `shift_lock`, `states`, `sticky`, `repeatable`, `functional`, `slide_cursor`, `slide_delete`, `option`
- `switches` mapping (optional)
- `keyboard_switch_policy` mapping (optional)

### 2.4 `style.yaml`

- `style` mapping (optional if only chrome/liquid)
- `preedit`, `window`, `tool_bar`, `liquid_keyboard` (optional)
- Color fields in `tool_bar` must be hex or known color-key references

### 2.5 `color.yaml`

- `preset_color_schemes` mapping
  - Each scheme:
    - `light` and/or `dark` palette mappings
    - or legacy flat palette
  - Each color value must be a hex literal (`0x...` / `#...`)
  - `name` / `author` are metadata, not colors
- `fallback_colors` mapping (optional)
  - values are hex or known color-key references

### 2.6 `resources.yaml`

- `resources` list of strings
- Each path must exist in the package

## 3. What a complete validator must check

1. **Schema validation** — every component file conforms to its schema.
2. **Semantic validation** — component manifest operations:
   - `add` target does not already exist
   - `override` / `remove` target exists
   - no include cycles
   - unknown component references fail
3. **Cross-reference validation** after resolution:
   - every key action references a known preset key (or is literal)
   - every `select:` references a known keyboard or special selector
   - every `ascii_keyboard` / `landscape_keyboard` exists
   - every `keyboard_switch_policy` target exists
   - every `style.keyboards` entry exists
   - every `fallback_colors` reference is known
4. **Color validation**:
   - color scheme values are hex
   - tool bar / chrome colors are hex or known references
5. **Resource validation**:
   - every declared resource exists in the package
6. **Package validation**:
   - schema file exists and is a valid Rime schema
   - layout files exist
   - default keyboard exists after resolution

## 4. Where validation must run

| Entry point | Must validate |
|---|---|
| CLI `script/validate-definitions.py --check-shipped` | All shipped component files + manifests + cross-refs |
| In-app “Validate definition file” | Selected file + its component package |
| `ComponentThemeLoader.loadTheme` | Full component package before returning `Theme` |
| `SchemaLayoutPackageManager` install | Full package before install/merge |

## 5. Proposed unified flow

```text
input file(s)
  → schema validation (per file)
  → component manifest resolution (add/override/remove/include)
  → cross-reference validation (resolved sections)
  → color validation
  → resource validation
  → package validation
  → produce resolved Theme / install package
```

One validator core, used by all entry points.

## 6. Immediate next step

1. Expand `doc/component-schema.json` to cover all component file types.
2. Implement a single `ComponentValidator` in Kotlin that combines:
   - `ComponentResolver` semantic checks
   - `BehaviorVerifier` cross-ref checks
   - `validateColorLiterals`
   - new schema/resource/package checks
3. Wire it into CLI, in-app validator, theme load, and package install.
4. Add tests for each entry point.
