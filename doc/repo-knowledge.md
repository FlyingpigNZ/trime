# Trime Repo — Agent Knowledge Map

This document is a fast-onboarding map for an AI agent working in this
repository. It records *what actually exists today*: the package/component
model, the theme & toolbar data flow, the T9 input path, and the key classes
and files involved. Re-check this before re-reading the whole source tree.

> Generated during the "schema-level toolbar" + "T9 pinyin disambiguation"
> planning session. Update it whenever a structural fact changes.

---

## 1. What this repo is

**Trime** — an Android Rime input method app.

- Main app logic: Kotlin under
  `app/src/main/java/com/osfans/trime/`.
- Rime engine bindings / JNI: `app/src/main/jni/` (`librime` and
  `librime_jni` are *upstream submodules* — **do not edit**; see CLAUDE.md rule 4).
- Proprietary schema packages (the "万象/简纯" IME layouts) live in
  `sample_theme_schemas/<包名>/` and are **not** built into the APK — they are
  user-importable self-contained packages.
- Validation / packaging / test tooling lives in `script/*.py` and is invoked
  from CI and the Makefile.

---

## 2. The self-contained IME package model

A package is a zip (or an on-disk package dir) that carries **all** of its own
definitions — there is **no** global app-shipped `standard` catalog to inherit
from. Overriding is allowed only *within* the package.

Shape of a package workspace (`app/src/main/assets/shared/Default/` shows the
built-in default package, and `sample_theme_schemas/<包名>/` shows customer
packages):

```text
manifest.yaml       # package identity + component composition (drives everything)
keyboard.yaml       # preset_keyboards
behavior.yaml       # preset_keys / switches / switching policy
color.yaml          # preset_color_schemes + fallback_colors
style.yaml          # style / preedit / window / tool_bar / liquid_keyboard
chrome.yaml         # optional split holding preedit / window / tool_bar
liquid_keyboard.yaml
rime/               # Rime schemas, dictionaries, Lua, OpenCC, .gram
resources/          # backgrounds, fonts, sounds, images
components/         # optional local component dirs
```

### manifest.yaml drives composition

```yaml
name: 万象14键
schema_id: wanxiang_14jian
default_keyboard: 14jian
components:
  - keyboard: {file: keyboard.yaml}      # composes preset_keyboards
  - behavior: {file: behavior.yaml}      # preset_keys / switches / policy
  - color:    {file: color.yaml}         # preset_color_schemes + fallback_colors
  - style:    {file: chrome.yaml}        # preedit / window / tool_bar
  - style:    {file: style.yaml}         # style + liquid_keyboard
  - schema:   {file: wanxiang_14jian.schema.yaml}   # Rime schema (not a Theme section)
rime_files:
  - rime/default.yaml                    # copied into Rime user data dir with rime/ stripped
```

Key component kinds (see `ComponentManifest.parseEntry`):
`schema`, `keyboard`, `behavior`, `color`, `style`, `resources`, plus string
`Reference` entries naming a local component directory.

### Resolution to a `Theme`

1. `ComponentResolver` (`data/theme/component/ComponentResolver.kt`) resolves
   the manifest into merged definition sections. For **named** sections
   (`preset_keys`, `preset_keyboards`, `preset_color_schemes`) later entries
   win **per named id**. For **scalar/map** sections (`style`, `preedit`,
   `window`, `tool_bar`, `liquid_keyboard`, `fallback_colors`) it
   deep-merges via `mergeMappings` (nested maps merged, leaf wins).
2. `ComponentThemeLoader.buildThemeNode` reassembles a single
   `Node.Mapping` containing `name` + the `THEME_SECTIONS`.
3. `Theme.decode` (`data/theme/Theme.kt`) parses it into the `Theme` model.

`THEME_SECTIONS` (order matters for the final node): `preset_keys`,
`preset_keyboards`, `preset_color_schemes`, `style`, `preedit`, `window`,
`tool_bar`, `liquid_keyboard`, `fallback_colors`.

### `__include` for keyboards

`preset_keyboards` entries may `__include: /preset_keyboards/<name>`.
`Theme.resolveKeyboardIncludes` inlines the includee and merges the includer's
own fields on top (cycles / unknown targets throw). This is how a package maps a
schema id to a real keyboard:

```yaml
wanxiang_t9:
  __include: /preset_keyboards/t9
```

### Validation tooling

- `script/validate-definitions.py <manifest.yaml>` — human entry point.
- `script/component_resolver.py` — pure-Python resolver (mirror of the Kotlin
  `ComponentResolver`).
- `script/behavior_verifier.py`, `script/color_verifier.py` — cross-ref checks.
- Kotlin runtime validation in `ComponentValidator` /
  `DefinitionValidator` (color literals, fallback cycles, known color keys).

Rules (CLAUDE.md):
- **schema-first**: if a definition can't be interpreted, fix the schema/data,
  not the consuming code. No hard-coded literals / magic numbers in code.
- Don't touch the `librime*` submodules; use the public `rime_api.h` surface.
- Keep generated assets (zips) in sync with their sources.

---

## 3. The `Theme` model & the toolbar

`Theme` (`data/theme/Theme.kt`) fields:
`name, generalStyle, preedit, window, liquidKeyboard, presetKeys,
presetKeyboards, colorSchemes, fallbackColors, toolBar`.

`ToolBar` (`data/theme/model/ToolBar.kt`):
`primaryButton, buttons, buttonSpacing, buttonFont, backStyle`. Each `Button`
has `background / foreground / action / longPressAction / size`.

### Toolbar read points (all currently read `theme.toolBar` directly)

| File | What it reads |
|---|---|
| `ime/bar/ui/AlwaysUi.kt` | `primaryButton`, `buttons` (left/right chrome) |
| `ime/bar/ui/ButtonsBarUi.kt` | `buttons`, `buttonSpacing`, firstButton size |
| `ime/bar/ui/TabUi.kt` | `buttons.firstOrNull`, `backStyle` |
| `ime/bar/InputBarDelegate.kt` | bar height from theme (indirect), option syncing |
| `ime/segments/SegmentsWindow.kt` | `buttonSpacing` |
| `data/theme/FontManager.kt` | `buttonFont` (FontKey.TOOLBAR_FONT) |

> Because there are many read points that all pull from one static
> `theme.toolBar`, per-schema toolbar override must be **centralized** (e.g. a
> resolver that computes the effective toolbar for the active schema) rather
> than patched at each call site.

### Theme lifecycle / schema layout overlay

`ThemeManager` (`data/theme/ThemeManager.kt`):
- `applySchemaLayout(layout, replaceTheme)`: `replaceTheme=false` merges a
  schema-layout theme on top of the decoration theme via
  `Theme.mergeSchemaLayout`, which merges `presetKeys`, `presetKeyboards`,
  `colorSchemes` only (chrome stays with the base). `replaceTheme=true`
  replaces the whole theme.
- `activeTheme` is the source of truth the UI/DI graph binds to.

`ThemeData` is provided into the DI graph (`InputDependencyManager`) as
`Theme`, so the `Theme` object is effectively immutable after activation;
rebuilding a theme rebuilds the DI graph.

### Built-in Default package

`app/src/main/assets/shared/Default/` — chrome.yaml here defines the
tongwenfeng-style toolbar (`button_spacing`, `button_font`,
`primary_button`, `buttons`).

---

## 4. Schema (Rime scheme) reading at runtime

- `RimeConfig` (`core/RimeConfig.kt`): thin JNI wrapper over the Rime
  config API. Supports `getInt / getString / getList / setBool`. `openConfig`
  opens `default`, `openSchema(schemaId)` opens a `.schema.yaml`.
- `RimeSchema` (`core/RimeSchema.kt`): reads `switches` (name/options/reset/
  states) and `speller/alphabet`. Used by the switch option window.
- Schema change notification: `Rime.kt` `updateSchemaCached` detects a changed
  `status.schemaId` and emits `RimeMessage.SchemaMessage(SchemaItem(...))`.
  `InputView.handleRimeMessage` forwards to `InputBroadcaster.onRimeSchemaUpdated`,
  and `KeyboardWindow.onRimeSchemaUpdated` calls `switchKeyboard(".default")`.
- **Raw schema files are also reachable directly**: the active package's
  workspace is `PackageStore.workspaceDir(activeId)`, and schema `.yaml` files
  listed in `rime_files` are copied there at install time, so a tool can parse
  them with the same `Yaml` util used by the theme loaders.

> This is the hook for the planned "schema-level toolbar" + "T9 pinyin
> disambiguation" features. The app can parse the active workspace's schema
> files at schema-switch time to read per-schema directives.

### 4.1 Planned: per-schema `.extended.yaml` (not yet implemented)

`.schema.yaml` is owned by the Rime engine (it compiles the input method), so the
app must **not** add application-specific sections to it. Instead the plan adds a
sibling file per schema, named after the schema id:

```text
rime/wanxiang_flypy_t9.extended.yaml
rime/wanxiang_t9.extended.yaml
```

It carries (a) the per-schema `tool_bar` override (`__replace: false`/`true`,
default merge) and (b) `t9_disambiguation` (`enabled`, plus the pinyin syllable
table / 双拼 key mapping). The app parses it with `Yaml`; the Rime engine never
touches it. See `doc/t9-toolbar-plan.md` for the full proposal.

---

## 5. T9 input path (digit keys → Rime candidates)

- T9 keyboard `t9` (`keyboard.yaml`): 4 rows. **First column of each row is a
  punctuation key** (`，`/`。`/`？`/`！`). Digit keys 2-9 carry letter hints
  (ABC/DEF/…), key `1` is the split-word key. Bottom row: `！` 符号 空格 数字 中英.
- T9 schemas: `wanxiang_t9` (full-pinyin 9-key) and `wanxiang_flypy_t9`
  (small-he 双拼 9-key). Both set `speller.algebra.__patch` to
  `- wanxiang_algebra:/9jian` (full-pinyin) or
  `- wanxiang_algebra:/base/小鹤双拼` + `- wanxiang_algebra:/9jian` (双拼).
- `/9jian` algebra preset (`rime/wanxiang_algebra.yaml`): normalizes tones,
  uppercases, then `xlit/ABCDEFGHIJKLMNOPQRSTUVWXYZ/22233344455566677778889999/`
  — i.e. **letters fold to T9 digits deterministically**;
  `xform/ⅱ//` is a marker Lua uses to classify input-method type.
- `super_processor.lua`: for `env.is_t9`, digit keys are **allowed through to
  the engine** (they are encoding keys, not select/option digits).
- `wanxiang.lua` `get_input_method_type`: special-cases `wanxiang_flypy_t9` →
  `"t9"` (otherwise the `Ⅲ` flypy marker would win and the dual
  `Ⅲ`+`ⅱ` markers would misclassify it as `flypy` → digit keys treated as
  tone/select, input breaks).
- `super_comment_preedit.lua` `SCHEME_CAPABILITIES`: each schema id declares
  `{tone, aux, pro, lite, t9}`. `wanxiang_t9`, `wanxiang_t9i`,
  `wanxiang_flypy_t9` have `t9=true`. This is where the "is this a T9 scheme"
  flag lives in the Lua layer.

### How pinyin is rendered/displayed for T9 today

- `super_comment_preedit.lua` `convert_t9_syllable`: for a single digit it
  shows an abbreviation (initial), multi-digit syllables convert to full
  pinyin from the candidate comment.
- The old `t9_preedit.lua` (in `简纯+14键`) is the simple "replace the preedit
  digits with their pinyin/english" filter.
- There is **no user-facing "list of all legal pinyin parses for this digit
  string"** today. That is the gap feature ② fills.

### Rime text/send APIs available to the app

`RimeApi` (`core/RimeApi.kt`): `simulateKeySequence`, `commitComposition`,
`clearComposition`, `getRawInput`, `selectCandidate`, `changeCandidatePage`,
`getCandidates`, `setRuntimeOption`, `getRuntimeOption`, `currentSchema`,
`processKey`, `moveCursorPos`.

> `LiquidWindow.triggerSymbolInput` is the in-repo example of "commit then feed
> a key sequence to Rime then attach the keyboard window" — the pattern to copy
> for the disambiguation panel's click-to-send.

---

## 6. Package install / activate pipeline

`data/schema/`:
- `PackageArchive` — extract zip into the package dir + workspace.
- `PackageMetadata` — read/write `manifest.yaml`, `schema_id`, schema list.
- `PackageStore` — package library / active package pointers / workspace dirs.
- `PackageActivator` — activate a package (compile if needed, set active,
  restart Rime, restore theme); Default-package bootstrap.
- `PackageCompiler` — runs the `:compile` process, waits on `compiled.marker`
  / `compiled.error`, restarts Rime & restores theme when changing the active
  package.
- `SchemaListUpdater` — writes `default.custom.yaml` `patch.schema_list`.
- `DefaultKeyboardRegistry` — `default_keyboard` ↔ schema_id bindings used by
  `KeyboardSwitcher.resolveDefaultKeyboard`.

`PackageThemeLoader` (`data/theme/PackageThemeLoader.kt`) — the entry that
loads a workspace into a `Theme` (component manifest first, else `theme.yaml`).

---

## 7. Where things live (quick file index)

Toolbar / theme:
- `data/theme/model/ToolBar.kt` (the model; merge target for feature ①)
- `data/theme/Theme.kt` (Theme + mergeSchemaLayout + resolveKeyboardIncludes)
- `data/theme/ThemeManager.kt` (activeTheme + applySchemaLayout)
- `data/theme/component/{ComponentResolver,ComponentValidator,ComponentThemeLoader,ComponentManifest,ComponentSource}.kt`
- `data/theme/DefinitionValidator.kt` (color literals, fallback cycles)
- `ime/bar/ui/{AlwaysUi,ButtonsBarUi,TabUi}.kt`
- `ime/bar/InputBarDelegate.kt`, `ime/segments/SegmentsWindow.kt`

Input / T9:
- `ime/keyboard/{Keyboard,KeyboardView,KeyboardSwitcher,KeyboardWindow,KeyView}.kt`
- `ime/composition/{CandidatesView,PreeditUi}.kt`
- `ime/candidates/compact/{CompactCandidateDelegate,CompactCandidateViewAdapter}.kt`
- `ime/symbol/{LiquidWindow,LiquidData}.kt` (the send-to-Rime pattern to copy)
- `rime/lua/wanxiang/{super_processor,super_comment_preedit,wanxiang,super_replacer,context_reorder}.lua`
- `rime/wanxiang_algebra.yaml` (`/9jian` preset)

Schema reading:
- `core/{RimeConfig,RimeSchema,RimeApi,Rime,RimeMessage,RimeProto,RimeUiState}.kt`
- `data/schema/{PackageStore,PackageMetadata,PackageActivator,PackageCompiler}.kt`

---

## 8. Constraints & conventions (from CLAUDE.md)

1. No hard-coded literals / magic numbers to make a broken definition load.
   Fix the data/schema first.
2. No editing the `librime*` upstream submodules; use the public `rime_api.h`.
3. Data inconsistency → normalize the data, don't loosen the parser.
4. Keep generated assets (zips) consistent with their source schemas.
5. Validate definitions early and fail loudly (schema-first).
6. Do not push unverified/unfinished work to remote.
