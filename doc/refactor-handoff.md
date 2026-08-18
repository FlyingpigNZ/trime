# Session Handoff — Trime Refactor (untangle engine / definitions / theme)

**Last updated:** 2026-08-19 · **Branch:** `refactor/untangle-ime-engine`
**Base:** `develop` @ `6c3b2bce` (merge of `upstream/develop` into fork `develop`)
**Goal:** refactor Trime to (1) untangle the Rime engine from the Android UI,
(2) make IME/keyboard definitions easy to use, (3) make theme/color definitions
easy to use — by splitting themes into a composable three-tier model.

> This file is the durable session memory. If the conversation is compacted or
> restarted, read THIS file first, then `doc/refactor-plan.md` (the full plan).

---

## 0. Repo / remote state

- Workspace: `/home/jin/Sources/my_projects/trime`
- `origin` = **fork** `https://github.com/FlyingpigNZ/trime` (this is what local
  `develop` tracks; "we trace the fork")
- `upstream` = canonical `https://github.com/osfans/trime.git` (fetch-only use)
- Local `develop` == `origin/develop` == `6c3b2bce` (synced, 0 ahead/0 behind).
  The fork was synced earlier this session: `git merge upstream/develop` pulled
  119 upstream commits + tags v3.3.10/v3.3.11, one conflict resolved (see §5),
  submodules bumped (librime 1.17.0, librime-lua) and pushed to origin.
- **Guiding decision (§0 of plan):** design against the fork's current state
  only; do NOT design around speculative upstream changes. Upstream syncs are
  mechanical and can happen anytime.

---

## 1. What has been done in this session

1. Synced fork `develop` with upstream (merge `6c3b2bce`, conflict resolved).
2. Created branch `refactor/untangle-ime-engine` from `develop`.
3. Ran 4 parallel deep-dive code analyses (Rime engine boundary, theme/color
   system, IME-definition system, UI layer/message flow) — all **confirmed the
   user's hypotheses** (evidence summarized in §3, full detail in plan §1).
4. Wrote `doc/refactor-plan.md` — the full 21-item phased plan, three-tier
   composable definition model, and all design decisions.
5. This handoff file.
6. **Implementation sessions**: Phase 0 (core/ Android-free + typed protocol),
   Phase 1 (daemon split, DI injection, RimeUiState, message flow), Phase 2
   items 10-13 (KeyAction split + typed commands, `__include` inheritance,
   KeyboardSwitcher). See §7 for the full commit list and remaining work.
7. **Unit-testing strategy** added to plan §6 (was: no tests existed).
8. **Phase 0.5 DONE**: replaced the stale `GeneralStyleTest` and added a pure
   JVM Kotest batch (`KeyActionDefinition.parse`, `KeyActionCommand.fromName`,
   `Theme.decode` + `__include`, `RimeMessage.nativeCreate`, `RimeUiState`).
   To make `KeyActionDefinition.parse` JVM-testable, introduced
   `KeyLabelProvider` (Android + pure ASCII provider) and made `KeyCode`
   prefer the generated Rime mapping before the Android fallback. Targeted
   `testDebugUnitTest` passes (21 tests).
9. **Phase 2 item 8 (core) DONE**: added app-shipped standard catalog files
    (`assets/shared/standard/{preset_keys,keyboards,colors}.yaml`), a
    `StandardCatalog` loader, and `ThemeResolver` that merges the catalog under
    each theme (theme overrides win). `trime.yaml` is now decoration-only;
    legacy monoliths (e.g. `tongwenfeng`) remain loadable as overrides. Schema
    tier still lands with item 12.
10. **Phase 2 item 9 DONE**: made tier-1 selection explicit-by-name via
    `use_standard_preset_keys: true`, `standard_keyboards: [...]`, and
    `standard_color_schemes: [...]`; unknown declared names fail validation;
    selecting a keyboard pulls in its `__include` dependencies. `__include`
    now fails loudly on unknown targets, and the legacy `import_preset` theme
    alias is retired (the field was removed from `TextKeyboard`).
11. **Schema-tier reference pair + manifest model**: created
    `sample_theme_schemas/minimal-14jian/` (manifest + minimal schema + minimal
    layout) derived from the user's full samples, plus a ThemeResolver test that
    resolves the minimal layout against the shipped standard catalog. Added
    pure `SchemaLayoutManifest`/`SchemaLayoutRegistry` (including `resources`
    for background images), and `KeyboardSwitcher` now consults the registry for
    explicit schema→default-keyboard bindings. The legacy alphabet heuristic
    was removed: unbound schemas use the theme's explicit default keyboard.
    Item 12 is now DONE: `SchemaLayoutPackageInstaller` unpacks zips (with
    zip-slip protection), `SchemaListUpdater` adds the new schema to
    `default.custom.yaml` as the default input method,
    `SchemaLayoutPackageManager` orchestrates install + Rime deploy +
    registration, `ThemeResolver.mergeSchemaLayout`/`Theme.mergeSchemaLayout`
    merge package layouts onto the active theme, and Profile settings has an
    on-device "Install schema layout package" action.
12. **Phase 3 item 19 DONE**: color schemes are now self-contained light/dark
    pairs (`light:`/`dark:` blocks). Legacy flat schemes still work and use
    their light palette as the dark fallback. `ColorManager`'s 4-branch
    `light_scheme`/`dark_scheme` logic is removed; night mode just selects the
    scheme's `darkColors`.
    **Standard resource delivery**: `standard/colors.yaml` and
    `tongwenfeng.trime.yaml` have been converted to the new `light:` shape
    (dark fallback = light). Legacy flat schemes are still accepted for
    compatibility.
13. **Phase 3 item 18 DONE**: `ColorManager` now merges the builtin fallback
    table with theme `fallback_colors` once per theme switch and resolves from a
    single combined map; duplicated builtin fallback comments were removed from
    shipped theme files.
14. **Definition spec + standard colors migration DONE**: added
    `doc/definition-schema.md` and updated `doc/trime-schema.json` for the
    three-tier YAML shapes (standard catalog, decoration theme, schema-layout
    package manifest/fragment). Converted `standard/colors.yaml` to the new
    self-contained `light:` shape.
15. **Phase 2 item 14 DONE**: `DefinitionValidator` core, in-app “Validate
    definition file” action, and `script/validate-definitions.py` CLI (with
    `--check-shipped` for CI).
16. **Phase 3 item 20 DONE**: removed `@Parcelize`/`Parcelable` from `Theme`
    and all theme model classes; they are now plain immutable JVM-friendly
    models.
17. **Phase 3 item 15 DONE**: added `ThemeColor` enum and migrated all static
    color/drawable string literals to typed enum calls.
18. **Phase 3 item 17 DONE**: `ColorManager` precomputes a resolved palette on
    scheme/light-dark switches and reads from it in `getColor`/`getDrawable`.
19. **Phase 3 item 16 DONE**: added `ThemeContext` facade over
    `ThemeManager`/`ColorManager`/`FontManager`/`KeyActionManager`.
20. **Automated app testing**: added `doc/automated-testing.md` and a
    compiling `SmokeTest` androidTest that launches `MainActivity` and checks
    the default theme loads. Running `connectedDebugAndroidTest` still needs an
    emulator/device.
21. **Phase 3 item 21 DONE**: added `BaseInputView.restyle()` and overrides;
    color changes now restyle existing input/candidate views in place. Theme
    structure changes still use full rebuild as a safe fallback.
22. **Canonical install workflow recorded**: user picks a schema-layout zip →
    extract + merge with standard → copy package resources to user backgrounds
    → deploy Rime schema → add to `default.custom.yaml` as default → activate.
    Legacy monolithic theme files are not part of this workflow.
23. **Full `简纯+14键` conversion DONE**: added
    `sample_theme_schemas/简纯+14键/` — a tier-2 `theme.yaml` (decoration +
    general keyboards/keys/colors as light/dark pairs) plus a tier-3 package
    (`manifest.yaml`, full `14jian.schema.yaml`, self-contained
    `14jian.layout.yaml`). The conversion is generated by
    `script/split_legacy_theme.py`, which expands the legacy
    `conf`/`styl`/`__patch` indirection into explicit fields; all reference
    definitions pass `script/validate-definitions.py --check-shipped`.
24. **Component model design started**: `doc/component-model.md` proposes
    replacing monolithic themes with composable components
    (`schema`/`keyboard`/`behavior`/`style`/`color`/`resources`/`shared-aux`)
    and deterministic `include`/`add`/`override`/`remove` semantics.
    Decisions so far: `shared-aux` derives from tongwenfeng; behavior owns
    switches/keyboard-switching policy; tongwenfeng becomes the shared base;
    `简纯+14键` becomes the first real consumer. A pure-Python reference
    prototype (`script/component_resolver.py` + tests) validates the
    layering/override semantics. `doc/component-schema.md` defines the
    precise manifest/file schema. `sample_theme_schemas/shared-aux/` is now
    extracted from tongwenfeng, and
    `sample_theme_schemas/tongwenfeng/manifest.yaml` is a thin component
    manifest reproducing tongwenfeng as `standard` + `shared-aux`.
    `sample_theme_schemas/简纯+14键/component.yaml` now composes
    `standard` + `shared-aux` + schema package with only the differing
    same-name keyboards/behaviors/colors/style/liquid as deltas
    (`keyboard.yaml`, `behavior.yaml`, `color.yaml`, `style.yaml`).
    Option A cleanup done: `shared-aux` is the clean tongwenfeng base
    (keyboards + behaviors only); style/color/liquid/chrome are
    theme-specific. Standard selection (`use_standard_preset_keys`,
    `standard_keyboards`, `standard_color_schemes`) is supported by the
    resolver. Duplicate keys in the 简纯+14键 monolith were fixed.
    Equivalence tests (`script/test_equivalence.py`) pass for tongwenfeng and
    简纯+14键 (component-assembled == normalized monolith + selected standard).
    A behavior verifier (`script/behavior_verifier.py` and Kotlin
    `BehaviorVerifier.kt`) validates every key action and reports zero errors
    on both real manifests.
    The component resolver is ported to Kotlin
    (`data/theme/component/`) with unit tests; full `testDebugUnitTest`
    passes. Machine-readable `doc/component-schema.json` added.
25. **Component model reference implementation DONE**: Python prototype +
    Kotlin resolver (`data/theme/component/`) + `DefinitionValidator`
    component-manifest support + JSON schema + shared-aux extraction +
    thin tongwenfeng and 简纯+14键 component manifests. Full
    `testDebugUnitTest` passes.
26. **Runtime component theme loading DONE**: `ComponentThemeLoader` resolves
    a component manifest into a runtime `Theme`; `ThemeFilesManager` discovers
    component themes (`<id>.component.yaml` or `<id>/component.yaml|manifest.yaml`)
    alongside monolithic `*.trime.yaml`; `ThemeManager` tries the component
    manifest before the legacy deployed file. Unit tests cover the loader.
27. **Shipping boundary clarified**: app assets ship standard + `shared-aux` +
    built-in themes. 简纯+14键/14jian is a **customer-defined input method
    package** and must be delivered as a zip (manifest + schema + layout), not
    bundled in app assets. The zip is generated from
    `sample_theme_schemas/简纯+14键/` (see README).
28. **Unified component validation DONE**: complete schema in
    `doc/component-schema.json`, gap analysis in
    `doc/component-validation-gaps.md`, `ComponentValidator` (per-file +
    resolution + behavior + color), wired into theme load, in-app validator,
    CLI (`--check-shipped`), and schema-package installer. Full unit tests
    pass.
29. **Data/schema fixes + repo rules**: tongwenfeng colors normalized to hex,
    chrome `normal: 0` → `0x00`, schema-first key validation (keys are
    non-empty mappings; spacers allowed), `CLAUDE.md` repository rules added
    (no data-fix literals in code, no magic numbers, fix definitions before
    hacking code). Component theme loading is validated end-to-end on-device.
30. **Customer package flow (resources integrated; on-device verification
    pending)**: `简纯+14键.zip` generated by `script/package_schema.py` (now
    includes `manifest.yaml` + `rime_files`). The package previously shipped
    the **full rime-ice schema** without its external resources (`melt_eng`,
    `radical_pinyin`, `rime_ice`, Lua, `symbols_caps_v.yaml`, dictionaries,
    OpenCC). New `rime_files` manifest support makes the package Rime-side
    self-contained: `SchemaLayoutManifest` parses it,
    `SchemaLayoutPackageInstaller` validates/extracts them, and
    `SchemaLayoutPackageManager.copyRimeFiles()` copies them from the package
    workspace into the Rime **user data dir** (the original "all under rime"
    location) before deploy. `script/package_schema.py` pulls the listed files
    from the sibling `sample_theme_schemas/rime.雾凇/` folder (or
    `--rime-source`) into `rime/...` inside the zip. The regenerated
    `简纯+14键.zip` is ~15 MB and contains 33 Rime files. First on-device
    install surfaced `nonexistent config file '/storage/emulated/0/rime/build/14jian.schema.yaml'`.
    The fix is to copy the extracted files into the Rime user data dir and then
    call the existing `Rime.deployRimeSchemaFile` for each auxiliary schema
    first (`melt_eng`, `radical_pinyin`) and the main `14jian` schema last, so
    all compiled configs/dictionaries are produced in `/storage/emulated/0/rime/build/`.
    No new JNI interface is needed. On-device typing with the imported schema
    also hit a `BadTokenException` in `TouchEventReceiverWindow` when a
    composition update arrived while the preedit anchor was not attached; that
    window now guards on `isAttachedToWindow`. Unit tests and
    `script/validate-definitions.py --check-shipped` pass. **Remaining**:
    install the regenerated zip on a device/emulator and verify 14jian deploys
    and types.

31. **Package model redesign (implemented)**:
    the three-tier "customer package inherits app-shipped standard catalog"
    model was replaced by a **self-contained flat IME package model**. Each
    package is a zip with one `manifest.yaml`, root split YAMLs
    (`keyboard.yaml`, `behavior.yaml`, `color.yaml`, `style.yaml`,
    `liquid_keyboard.yaml`, `chrome.yaml`), and `rime/` resources. No
    `base/`/`standard/`/`shared-aux/` subfolders. Colors use a flat
    `colors` + `color_schemes` model: named palettes referenced by
    light/dark pairs, with pair names shown in UI. `Default.zip` and
    `简纯+14键.zip` are generated under `sample_theme_schemas/`.
    **Implemented**: `ImePackageManager` (list/import/activate/uninstall,
    active manifest, build clearing), `package_schema.py` packs self-contained
    component packages, install action imports into `/rime/IMEs/`, IME package
    picker in theme settings, active package theme restored on startup,
    `script/build_default_package.py` builds `Default.zip`, Python/Kotlin
    resolvers and `Theme.decode` support the flat color model.
    **Emulator fixes**: theme application moved to main thread
    (`a71b8e3c`), `default.custom.yaml` schema list is replaced on package
    switch (`8989a697`). A fallback cleanup commit was reverted (`e9fa4b49`)
    after user review — not required.
    **Remaining**: further emulator testing (package switch cleanliness,
    color pair UI, active restore), and possibly UI work for color pair names.
32. **IME package activation hardening (this session)**: package activation is
    now a blocking flow with a modal “Deploying…” dialog and the deploy
    notification restored. `ImePackageManager` stores a SHA-256 package
    fingerprint in the active manifest; re-selecting the exact same package is
    a no-op, while an updated zip with the same name/schema re-deploys.
    Session-kill restore now selects the active package schema (suspend
    `runOnReady`) before applying the theme, and `ThemeManager.ensureInitialized`
    prevents the `_activeTheme` crash when the IME view is created before Rime
    is ready.
33. **Night mode fix**: `ColorManager.onSystemNightModeChange` now rebuilds the
    resolved palette before notifying listeners, so runtime day/night switches
    actually change colors.
34. **14jian data fixes**: light palettes changed from transparent `0x00…` to
    opaque `0xff…`; `Keyboard_letter_14jian` width reduced from 11.5 to 11 so
    `Return` stays on row 5; horizontal gap reduced from 16 to 8.
35. **Candidate width issue still OPEN**: user reports multi-character
    candidates look smaller than single-character ones in compact/unrolled
    candidate views. Debug logging showed `textSize=54`, `scale=1` for both,
    but item widths were 120px (1 char) vs 144px (2 chars) due a hardcoded
    `minimumWidth = dp(40)` and unrolled `flexGrow = 1f`. A fix removing the
    min width and unrolled flexGrow was tried and **reverted** after user said
    it did not work. The investigation/revert commits are `26e04854`,
    `ecbd207f`, `980bf346`, `2a5ea8b0`. Next step is to find the real
    rendering path (likely `AutoScaleTextView` or popup candidate UI) with
    more targeted logging or a user-provided screenshot.

---

## 2. Codebase structure (as of `6c3b2bce`)

238 Kotlin files, ~25k lines under `app/src/main/java/com/osfans/trime/`:

- **`core/`** — Rime engine wrapper: `Rime.kt` (519 ln, THE tangle),
  `RimeApi.kt` (clean suspend interface), `RimeProto.kt` (pure data:
  CommitProto/CandidateProto/CompositionProto/StatusProto/Candidates/RimeResponse),
  `RimeMessage.kt` (sealed, but int↔ordinal coupling), `RimeDispatcher.kt`
  (single-threaded rime-main executor), `RimeLifecycle.kt`, `RimeConfig.kt`,
  `RimeSchema.kt`, `SchemaItem.kt`, `KeyValue.kt`, `KeyModifier.kt`,
  `RimeKeyEvent.kt`.
- **`daemon/`** — `RimeDaemon.kt` (singleton, session refcounting, BUT also
  Android notifications), `RimeSession.kt` (client boundary), `Extensions.kt`.
- **`data/theme/`** — `Theme.kt` (@Parcelize + decode), `ThemeManager.kt`
  (singleton, Rime.deployRimeConfigFile coupling), `ColorManager.kt` (string-
  keyed colors, 3 fallback tables), `FontManager.kt`, `KeyActionManager.kt`,
  `ThemePrefs.kt`, `ThemeFilesManager.kt`, `ThemeItem.kt`, and `model/`
  (`GeneralStyle` ~50 fields, `TextKeyboard`, `TextKey`, `PresetKey`,
  `KeyActionToken`, `LiquidKeyboard`, `ToolBar`, `Window`, `Preedit`,
  `ColorScheme`).
- **`ime/`** — Android UI:
  - `core/`: `TrimeInputMethodService.kt` (993 ln, owns session + views +
    message dispatch), `BaseInputView.kt`, `InputView.kt` (Kodein DI init),
    `NavigationBarManager.kt`, `InlinePreeditMode.kt`, etc.
  - `keyboard/`: `Keyboard.kt`, `Key.kt` (9 ColorManager + 5 rime.run),
    `KeyAction.kt` (fused parse/interpret/render), `KeyBehavior.kt` (overloaded
    enum), `KeyCode.kt`, `KeyView.kt` (rime.run inside onDraw!),
    `KeyboardView.kt`, `KeyboardWindow.kt` (switch policy + engine reads),
    `CommonKeyboardActionListener.kt` (string command dispatch),
    `KeyboardSwitcher.kt` (deprecated stub).
  - `candidates/` (compact/, popup/, unrolled/), `composition/`, `bar/`,
    `broadcast/` (InputBroadcaster event bus), `dependency/`
    (InputDependencyManager Kodein), `window/`, `symbol/` (LiquidData),
    `popup/`, `switches/`, `clipboard/`, `dialog/`, `segments/`.
- **`util/yaml/`** — custom SnakeYAML event parser → sealed `Node` AST.
- **`ui/`** — settings/main activities, `ui/main/settings/theme/` etc.
- Assets: `app/src/main/assets/shared/` — 2 shipped themes:
  `trime.yaml` (1716 ln) and `tongwenfeng.trime.yaml` (3949 ln); both mix
  colors + 50 keyboards. Also 6 `.schema.yaml` files, `prelude/` (untracked,
  contains default.yaml etc.), and an untracked `简纯+14键.trime.yaml`.

---

## 3. Key findings (evidence — all verified, details in plan §1)

### H1: Engine/Android tangle — TRUE (JNI seam is clean)
- `Rime.kt` mixes JNI + response synthesis + cache + **AppPrefs reads**
  (:69-70) + Android side effects (DataManager :210-212, OpenCC :295).
- Presentation in engine: `handlePreedit` (:258-276), `showAsciiSwitchTips`
  (:332-348) — imports UI enum `InlinePreeditMode`.
- UI pulls engine internals: 20+ `rime.run { statusCached }` sites in
  Key/KeyAction/KeyView/KeyboardWindow/InputView/etc. — **no @Volatile,
  cross-thread data race**.
- `KeyView.kt:382-383`: `rime.run { !getRuntimeOption(...) }` (runBlocking)
  **inside onDraw**.
- `RimeDaemon.getFirstSessionOrNull()!!` from leaf views (Key.kt:21,
  KeyAction.kt:72, KeyView.kt:45).
- Message protocol: C++ sends ints 1-3, Kotlin synthesizes ints 4-10
  (Rime.kt:246-255,229-232) ↔ `MessageType.ordinal()` (RimeMessage.kt:136-139)
  — runtime ClassCast/IndexOutOfBounds risk. `messageFlow` = MutableSharedFlow
  buffer 15 DROP_OLDEST (silent loss).
- `RimeDaemon.run()` uses runBlocking (RimeDaemon.kt:65); daemon mixes
  notifications (RimeDaemon.kt:117-127,129-163).
- `ThemeManager.kt:62` calls static JNI `Rime.deployRimeConfigFile` — theme
  loading routed through the engine.
- Nuance: codebase is mid-refactor — Kodein DI (InputDependencyManager),
  InputBroadcaster event bus, EventStateMachine, recent "decouple ToolButton
  from RimeDaemon" commits all point the right way.

### H2: Theme/color overcomplicated — TRUE
- 73-78 ad-hoc `ColorManager.getColor/getDrawable` call sites, string keys.
- 3 parallel fallback tables (scheme → fallback_colors → 40-entry
  BuiltinFallbackColors ColorManager.kt:52-94,205-229), builtin duplicated as
  comment in trime.yaml:96-122.
- Night mode: 4-branch when + magic light_scheme/dark_scheme keys in color map
  (ColorManager.kt:138-163).
- 4 mutable global singletons, order-sensitive resets on theme switch.
- Color change → full view teardown/rebuild (3 overlapping triggers in
  TrimeInputMethodService.kt:104-128).
- @Parcelize + decode() with `!!` crashes on bad YAML.
- GeneralStyle: ~50 flat fields, hand-written decode defaults.

### H3: IME definition overcomplicated — TRUE
- KeyAction fuses parsing (3 token forms + preset lookup) + interpretation +
  rendering, reaching live Rime state + AppPrefs.
- Stringly-typed commands: `when (action.command)` ~15 literals
  (CommonKeyboardActionListener.kt:178-193), `"_keyboard_"`/`"_key_"` prefix
  matching (KeyboardWindow.kt:290-309), keycode↔name↔Rime-value round-trip.
- KeyBehavior overloaded: gestures + state conditions in one enum.
- **`__include` declared but unimplemented** — trime.yaml:1051,1508 use it;
  nothing in Kotlin reads it; TextKeyboard.decode silently drops it. Only
  working inheritance is `import_preset` (TextKeyboard.kt:157,
  KeyboardWindow.kt:101-103).
- KeyboardSwitcher deprecated stub still read at
  CommonKeyboardActionListener.kt:121,332,338 + PopupKeyboardUi.kt:198.
- Theme monolith: tongwenfeng 3949 ln (colors 154-861, keyboards 1061-3949,
  50 keyboards); trime.yaml 1716 ln. Smart keyboard matching = naming
  convention + alphabet guess (KeyboardWindow.kt:148-163).

---

## 4. The design (three-tier composable definition model)

Runtime `Theme` object keeps its merged shape; a `ThemeResolver` merges tiers
at load. Definition-layer change only — no UI rewrite.

| Tier | Contents | Owner | Changes |
|---|---|---|---|
| 1. Standard catalog | standard keyboards (qwerty/letter/number/symbols/editor/emoji/liquid), standard color schemes, preset_keys | **app** — ONE yaml file each | rarely |
| 2. Decoration | color schemes (overrides+new), fallback_colors, backgrounds, fonts, chrome (preedit/window/tool_bar), **entire generalStyle** | theme author | per-theme |
| 3. Schema layouts | non-standard keyboards bound to a Rime input (T9, 14-key, cangjie) | schema author, travels with schema | with schema |

### Settled decisions (D1-D3 + others)
- **D1 — explicit-by-name references only**: `qwerty`, `26key`, `symbol`,
  `emoji`, …; no implicit merging; validated at parse time.
- **D2 — validator**: shared validation core, delivered **both** in-app
  (settings action) + CLI tool (scripts/, CI). Catches unknown refs, malformed
  key actions, invalid color keys, `__include` cycles, missing fields.
- **D3 — versioning low priority**: parsing format unlikely to change; keep
  `config_version`; no min-version machinery.
- **Schema pairing**: schema + custom layouts travel together as **separate
  files** (Rime must compile schema alone), **delivered as a zip** (schema +
  layouts + manifest); app unpacks, compiles via Rime, registers layouts.
- **`__include` is the single inheritance mechanism** (retire `import_preset`,
  migrate KeyboardWindow.kt:101-103 usage).
- **generalStyle wholly in decoration tier.**

### Still open (minor, decide during implementation)
1. Zip package layout / manifest format.
2. Validator check scope/order.

---

## 5. Session notes / gotchas

- **Upstream merge conflict** (earlier this session): `BaseInputView.kt`
  conflicted — resolved to upstream's mask-based `getNavBarBottomInset` +
  `ignoreSystemGestureInsets` preference (per user: "use upstream
  implementation"); file matches upstream byte-for-byte. `TrimeInputMethodService.kt`
  auto-merged (fork's change already matched upstream).
- **Git credential gotcha**: `~/.git-credentials` PAT was expired; sandbox home
  is read-only so `store` helper can't write lock. User updated their real
  credential file properly ("no worries, I've updated it properly"). If pushing
  fails again: use a workspace-local credential helper + `-c
  credential.helper=` to clear the broken store helper. NOTE: a past helper
  bug — `${cred#*:}` grabs `//FlyingpigNZ:...`; must strip `https://` first.
- **HOST OOM (end of session 2)**: a Gradle run in this sandbox OOM'd the whole
  host machine (not a cgroup limit — the host itself ran out of memory, ~93GB
  total). Trigger: `testDebugUnitTest`/`compileDebugUnitTestKotlin` with
  `-Xmx4096M` daemon heap + in-process Kotlin compiler + forked test JVM +
  re-unpacking Gradle 9.5.1 after `/tmp` was cleared (caches vanished).
  Recovery: `pkill -f gradle`; host returned to 89GB free. **Avoid long Gradle
  runs in this sandbox — prefer the user's own machine for builds/tests.**
  If a Gradle run is unavoidable: `/tmp` is wiped between shell commands, so use
  a workspace-persistent home: `GRADLE_USER_HOME=$PWD/.gradle-test-home
  ./gradlew ...`. Keep `.gradle-test-home` on disk between sessions (do not
  delete it; it is untracked and must not be committed). Also keep the local
  `gradle.properties` change `kotlin.compiler.execution.strategy=in-process`
  on disk uncommitted — it avoids the read-only Kotlin daemon directory in this
  sandbox. Expect the first run to download Gradle 9.5.1 + dependencies; later
  runs are fast. Be ready to kill the daemon (`pkill -f gradle`).
- **No meaningful unit tests existed** (2 stale files: `GeneralStyleTest.kt`
  references `Theme.decodeByConfigId` + `Rime.startupRime`, both gone;
  `WeakHashSetTest.kt` ok). JUnit5 + Kotest infra IS configured
  (`app/build.gradle.kts` `testOptions { unitTests { useJUnitPlatform() } }`).
  New testing strategy in plan §6: pure JVM targets only (`KeyActionDefinition
  .parse`, `KeyActionCommand.fromName`, `Theme.decode` + `__include`,
  `RimeMessage.nativeCreate`, `RimeUiState`, `KeyboardSwitcher.resolveKeyboard`
  with a fake session) — **no JNI, no Robolectric, no `Rime.startupRime` in
  tests** (the old test did exactly that and is why it broke). Start with a
  "Phase 0.5" test batch locking in the refactor so far.
- Working tree currently has: modified submodule `OpenCC` (M), dirty
  `librime-lua-deps` (m, pre-existing local deletions of lua5.3 files — do NOT
  touch/commit), untracked `app/release/`, `app/src/main/assets/prelude/`,
  `简纯+14键.trime.yaml`, `doc/refactor-plan.md`, `doc/refactor-handoff.md`.

---

## 6. The plan (full detail in `doc/refactor-plan.md`)

- **Phase 0 — Foundations (no behavior change):** 1. type the message protocol
  (RimeMessage.create instead of ints; thin fromNative adapter for C++);
  2. extract prefs+presentation out of Rime (InputOptions interface; core/
  becomes Android-free); 3. decouple data-dir/OpenCC wiring.
- **Phase 1 — Engine boundary:** 4. split RimeDaemon (DeployNotifier);
  5. remove getFirstSessionOrNull()!! from views (inject via DI);
  6. observable StateFlow<RimeUiState> replaces pull-caches (fixes data race);
  7. fix silent-loss buffering + runBlocking.
- **Phase 2 — Composable definitions:** 8. split monolith into 3 tiers +
  ThemeResolver (keep legacy themes loadable); 9. explicit declaration +
  `__include` working + retire import_preset; 10. KeyAction parse/interp split
  (ActionDefinitionParser + sealed Action types); 11. typed commands;
  12. declared schema↔layout binding + zip delivery; 13. extract switch policy
  from KeyboardWindow; 14. validator (in-app+CLI) + reference docs + schema.json
  + starter theme.
- **Phase 3 — Decoration:** 15. typed color keys (enum); 16. single ThemeContext
  (merge 4 singletons); 17. precomputed resolved palette; 18. unify fallback
  tables; 19. data-driven light/dark pairs; 20. decouple parsing from
  Parcelable; 21. incremental restyle instead of full rebuild.

Sequencing: Phase 0 → 1 strictly first (unblocks everything); Phases 2 and 3
can run in parallel (disjoint files, except items 6/16 touch
TrimeInputMethodService). Within Phase 2, item 8 first.

---

## 7. How to resume

1. Read this file + `doc/refactor-plan.md`.
2. Verify branch: `git branch --show-current` should be
   `refactor/untangle-ime-engine`; if not, `git checkout
   refactor/untangle-ime-engine`.
3. Current state (all plan phases complete):
   - **Phase 0 DONE**: core/ Android-free (Rime takes injected
     InputOptions/RimeEnvironment/hooks), typed message protocol
     (emitMessage + sealed RimeMessage; nativeCreate = C++ adapter 1-3).
   - **Phase 1 DONE**: DeployNotifier split (4); getFirstSessionOrNull()!!
     removed from views (5); RimeUiState StateFlow + hot-path migration (6);
     messageFlow on RimeSession + buffer 64 (7).
   - **Phase 0.5 DONE**: pure-JVM test batch added (see §1 item 9); targeted
     `testDebugUnitTest` passes.
   - **Phase 2 COMPLETE**: items 8-14 DONE (standard catalog + ThemeResolver,
     explicit declarations, KeyAction split, typed commands, schema-layout
     package flow, KeyboardSwitcher, validator + CLI + in-app).
   - **Phase 3 COMPLETE**: items 15-21 DONE (typed colors, ThemeContext,
     resolved palette, fallback tables, light/dark pairs, Parcelable removal,
     incremental color restyle).
   - **Post-plan resource standardization**: `standard/colors.yaml` and
     `tongwenfeng.trime.yaml` converted to new `light:` shape;
     `minimal-14jian/` is a self-contained schema-layout package (manifest +
     schema + single layout defining 14jian/letter_14jian and all non-standard
     preset keys); the full `简纯+14键` monolith is now split into a tier-2
     `theme.yaml` + tier-3 package under `sample_theme_schemas/简纯+14键/`;
     package resources are copied to user backgrounds on install.
   - **Self-contained IME package model (implemented)**: flat packages under
     `/rime/IMEs/`, single `manifest.yaml`, root split YAMLs, flat
     `colors` + `color_schemes`, `ImePackageManager`, IME package picker,
     startup restore, `Default.zip`/`简纯+14键.zip` generated. Emulator fixes:
     main-thread theme apply, schema list replacement on switch.
   - **IME package activation/restore hardened**: SHA-256 fingerprint guard,
      blocking deploy UI + notification, session-kill schema/layout restore,
      `ThemeManager` init guard, night-mode palette rebuild.
   - **14jian data fixed**: opaque colors, enter on row 5, horizontal gap 8.
   - **Candidate width issue still OPEN**: see §1 item 36; attempted fixes were
      reverted.
   - **Automatic app testing**: `doc/automated-testing.md` + compiling
     `SmokeTest` androidTest; running requires an emulator/device.
   - Commits on this branch: b8ba95b1 (CLAUDE.md rules) -> 8496934a
     (schema-first key validation) -> 7adca9d1 (spacer keys) -> c67cc11f
     (validation docs) -> b97fd2d9 (validation wiring) -> 80b10c9c (unified
     validator) -> 0746e312 (schema/gaps) -> 780504d6 (theme load validation)
     -> 0e96ff77 (color validator) -> 7de1f3ae (color hex normalization) ->
     09162e4a (standard root fix) -> 96964c42 (remove legacy asset) ->
     554c952d (shipping boundary) -> 338f71d9 (remove customer asset) ->
     b8aba90e (ship assets) -> 0d237347 (dup cleanup) -> 8104dfee (runtime
     loading docs) -> 70036fb1 (runtime loading) -> 66eb0666 (behavior
     verifier docs) -> ae2a925a (Kotlin behavior verifier) -> c5e1dd6a (full 14jian split) -> 5d333858 (docs refresh) -> d3ce11c6 (handoff update)
     -> afa48fc3 (package
     resource copy + workflow) -> 1cc7a0c1 (14jian self-contained) ->
     b48493b7 (14jian package split) -> e6f475a6 (tongwenfeng standardization)
     ->
     f3d9df3d (item 21 restyle) -> 13602d3a (instrumentation smoke test) ->
     9e53c2fb (automated testing docs) ->
     36fb5912 (item 16 ThemeContext) -> 77ff33a1 (item 17 resolved palette) ->
     b1b4fcb9 (item 15 typed colors) -> 91d1f78c (item 20 Parcelable removal)
     -> fffe3c38 (item 14 validator) -> 8405a3d4 (definition spec + standard
     colors) -> b1786358 (item 12 done) -> 15dd8947 (item 12 select flow +
     layout merge) -> bd71a067 (item 12 zip installer) -> d0681490 (item 18
     fallback tables) -> fa14cf80 (standard color migration TODO) ->
     35117fba (item 19 dark pairs) -> d7d1aac3 (keep Gradle temp) ->
     835618ce (drop alphabet heuristic) -> 37fb3ded (item 12 manifest/binding)
     -> a7d0a174 (schema-tier reference) -> 5cddcb88 (item 9) -> dc03bbed
     (item 8 core) -> f0ebd2b9 (Phase 0.5 tests) -> 75d13157 (docs) ->
     15f42b24 (10b) -> 8c05f7de (10a) -> b8114d2e (13) -> f94324d2 (7/11/12)
     -> a85102c2 (6) -> 61d35330 (5) -> 75eb8451 (4) -> f5503f1e (Phase 0) ->
     d5c46822 (docs) -> 7705c826 (IME package activation/restore + 14jian colors)
      -> 2ecdc287 (14jian enter row 5) -> d9f209af (14jian horizontal gap 8)
      -> 32fab871 (night mode palette rebuild) -> ecbd207f (revert candidate
      flexShrink experiment) -> 2a5ea8b0 (revert candidate width experiment).
4. Next step options:
   - (a) Run `./gradlew :app:connectedDebugAndroidTest` on an emulator/device
     to execute the instrumentation smoke test
   - (b) Decide on a dedicated import dialog/UX for schema packages (currently
     system file picker + toast)
   - (c) ~~Convert the full `简纯+14键.trime.yaml` monolith into the split
     package/theme resources~~ **DONE** — see `sample_theme_schemas/简纯+14键/`
     and `script/split_legacy_theme.py`
   - (d) user may raise something else
   - (e) **Implement the component model** from `doc/component-model.md`:
     component resolver + `shared-aux` extraction from tongwenfeng, then
     migrate `简纯+14键` onto it — **DONE** as reference implementation
   - (f) ~~**Integrate the Kotlin component resolver into runtime theme
     loading** so `ThemeManager` can consume component manifests instead of
     monolithic theme files~~ **DONE** — `ComponentThemeLoader` +
     `ThemeFilesManager` + `ThemeManager` integration
   - (g) **Verify component tongwenfeng on-device** (menu tests) and then
     decide whether to push / open a PR
   - (h) **Customer package flow**: Rime resources are now integrated via
     `rime_files`; verify the generated `简纯+14键.zip` end-to-end on a
     device/emulator (deploy + type 14jian)
   - (i) **Investigate candidate text-size inconsistency**: user reports
      multi-character candidates look smaller than single-character ones in
      compact/unrolled views. Previous min-width/flexGrow fixes were reverted;
      needs targeted logging in the actual rendered path or a screenshot.
5. Build gotcha: home dir is read-only in this sandbox, and `/tmp` is wiped
   between shell commands. For repeated Gradle runs use a workspace-writable
   user home, e.g. `GRADLE_USER_HOME=$PWD/.gradle-test-home ./gradlew ...`.
   Keep `.gradle-test-home` and the local `gradle.properties`
   `kotlin.compiler.execution.strategy=in-process` line on disk — they are
   untracked/uncommitted and should stay that way. If incremental compile
   errors look stale, add `--rerun-tasks`. **CAUTION: Gradle runs OOM'd the
   host machine at the end of session 2 — prefer compiling/tests on the user's
   own machine; if you must run Gradle here, kill the daemon afterward
   (`pkill -f gradle`).**

---

## 9. Test coverage goal & Android integration testing (open)

The Phase 0.5 batch locks in the first pure-JVM seams, but it is not full
project coverage. Goal: as the refactor proceeds, add pure-JVM unit tests for
every non-Android seam we touch:

- `core/` protocol/state types (done: `RimeMessage`, `RimeUiState`; extend to
  `RimeProto`/`RimeResponse`/`KeyValue` as needed).
- `data/theme/` decode + inheritance (done: `GeneralStyle`, `Theme` +
  `__include`; extend to `TextKeyboard`/`TextKey`, `PresetKey`,
  `LiquidKeyboard`, `ToolBar`/`Window`/`Preedit`).
- `ime/keyboard/` definitions and policy (done: `KeyActionDefinition`,
  `KeyActionCommand`; next: `KeyboardSwitcher.resolveKeyboard` with a fake
  `RimeSession`, `KeyBehavior`, `KeyCode` mapping).
- Validator core (Phase 2 item 14) must be pure and unit-tested.
- Any new `ThemeResolver`/catalog parser must be pure and unit-tested.

**Android integration testing is a separate track.** The project has
`androidTestImplementation(libs.junit)` but no instrumentation tests yet. Local
unit tests (`testDebugUnitTest`) deliberately avoid JNI/Robolectric. Real
integration tests (IME lifecycle, view rendering, Rime JNI round-trips) need an
Android emulator/device and can be run with:

```bash
./gradlew :app:connectedDebugAndroidTest
```

or from Android Studio. Those runs need the native librime build and are not
practical inside this sandbox. We can add a small instrumentation smoke test
later (e.g. service starts, theme loads) and document how the user runs it on
their machine/CI.

---

## 10. Session 2026-08-18: self-contained Default package, no theme concept, IME settings cleanup

### What changed

1. **Default package source is now `app/src/main/assets/shared/Default/`**
   - Contains the extracted `Default.zip` package files (`manifest.yaml`,
     `behavior.yaml`, `keyboard.yaml`, `color.yaml`, `style.yaml`,
     `chrome.yaml`, `liquid_keyboard.yaml`) and `rime/...` as symlinks into
     `app/data/rime`.
   - Build task `buildDefaultPackage` zips that folder into
     `app/src/main/assets/shared/Default.zip` before checksums/asset merge.
   - `androidResources.ignoreAssetsPatterns.add("Default")` keeps the source
     folder out of the APK; only `Default.zip` and generated `opencc/` ship.
   - `DataChecksumsPlugin` excludes `shared/Default` from checksums; checksums
     now contain only `shared/Default.zip`, `shared/opencc/**`, and `prelude/**`.
   - Removed old flat shared assets: rime symlinks, `trime.yaml`,
     `shared-aux/`, `standard/`, `tongwenfeng/`.

2. **Runtime now forces an IME package before any theme/color init**
   - `ImePackageManager.ensureDefaultPackageReady()`:
     - if `/rime/IMEs/active-manifest.yaml` exists → restore its theme
     - otherwise install bundled `Default.zip` into `/rime/IMEs` and activate it
   - Called in `TrimeInputMethodService.onCreate` before `ThemeManager.init`,
     and as a blocking guard in `onCreateInputView()` when Rime isn’t ready yet.
   - `ThemeManager` no longer has theme selection / legacy theme lookup.
     It only stores the active package Theme and applies it via
     `applySchemaLayout()`.
   - Removed `selected_theme` preference, `ThemePickerDialog`,
     `ThemeSettingsFragment` theme row, quick-switch Theme entry, and
     `set_theme` command.

3. **Settings UI**
   - New top-level **IME packages** settings screen (`ImeSettingsFragment`):
     - lists/activates packages from `/rime/IMEs` via `ImePickerDialog`
     - imports a package zip via file picker
   - Removed “Install schema layout package” from Profile settings.
   - Keyboard Style screen now only contains Colors.

4. **Schema list / package switching**
   - `ImePackageManager.activate()` now calls `cleanLegacySchemas()` before
     extracting a new package: deletes leftover `*.schema.yaml` in the Rime
     user data dir and removes legacy `schema-packages/`.
   - After writing `default.custom.yaml`, activation calls
     `updateConfig()` so the Schemata settings screen immediately sees the new
     package’s schemas.

5. **Dark mode fix**
   - `ColorManager.init()` now calls `rebuildResolvedPalette()` after setting
     `isNightMode`, because the `activeColorScheme` setter skips rebuild when
     the scheme instance is unchanged.

6. **14键 candidate text-size package fix (from earlier in session)**
   - In `简纯+14键` style: `candidate_view_height` 18→28,
     `candidate_padding` 6→5, `candidate_spacing` 0.5→0.0,
     `comment_height` 18→12, `comment_text_size` 12→10.
   - Suspected root cause: `AutoScaleTextView` scales multi-char text down when
     `candidate_view_height` is shorter than full font metrics.

### Current status

- `./gradlew :app:compileDebugKotlin` passes.
- End-to-end device flow verified: fresh install → auto-activate Default.zip →
  package switch to 简纯+14键 → switch back.
- Candidate text-size issue resolved via the 14键 package style
  (`candidate_view_height` 28, `candidate_padding` 5, `candidate_spacing` 0.0,
  `comment_height` 12, `comment_text_size` 10).
- `checksums.json` is build-generated and gitignored; do not commit it.
- Untracked workspace noise to ignore: `.gradle-test-home/`, `app/release/`,
  `app/src/main/assets/prelude/`, `sample_theme_schemas/rime.雾凇/`.

### Open items / likely next steps

- Verify dark mode now switches the full palette (especially with 简纯+14键,
  which has real dark pairs).
- The `onCreateInputView()` `runBlocking { ensureDefaultPackageReady() }`
  guard is a pragmatic stop-gap; a proper first-run setup/navigation step could
  replace it if first-activation latency is a problem.
- Decide whether quick-switch Theme entry and `set_theme` command removal is
  final (currently removed).

---

## 11. Session 2026-08-18: Rime user data moved to Android-managed storage

The full-storage-access requirement is gone. Rime user data now lives in
app-specific external storage (`getExternalFilesDir(null)/rime`) instead of the
public `/storage/emulated/0/rime`.

### What changed

- `DataManager.defaultDataDir` is now `getExternalFilesDir(null)/rime`; the old
  public `/rime` path is kept only as the one-time migration source.
- `DataManager.userDataDir` no longer reads a user preference. The
  `profile_user_data_dir` setting/UI is removed; the app always uses the
  app-managed dir.
- `DataManager.migrateLegacyUserDataIfNeeded()` runs right after
  `AppPrefs.initDefault()`: it copies any legacy `/rime` (or previously
  configured custom dir) into the managed dir once, marks completion in
  app-internal storage, and clears the old preference.
- Storage permissions removed from the manifest
  (`MANAGE_EXTERNAL_STORAGE`, `READ/WRITE_EXTERNAL_STORAGE`,
  `requestLegacyExternalStorage`), the setup wizard no longer has a
  permission step, and `Rime` no longer blocks startup on a storage-availability
  check.
- `RimeDataProvider` already exposed `getExternalFilesDir(null)`, so the new
  Rime data remains browsable through the app’s DocumentsProvider without
  granting broad storage access.
- Unit tests updated for the removed legacy asset paths (`ThemeResolverTest`)
  and the removed `set_theme` command (`KeyActionCommandTest`).

### Verified

- `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`
  pass (94 tests).
- Installed on emulator-5554: app launches, Rime starts, and existing data was
  migrated from `/storage/emulated/0/rime` to
  `/storage/emulated/0/Android/data/com.osfans.trime.debug/files/rime`.
- Installed package permission list contains only notification + dynamic
  receiver permissions; no storage permissions are requested.

### Remaining / notes

- The legacy public `/rime` directory is intentionally left in place after
  migration (non-destructive). It can be deleted manually once the user is
  satisfied.
- Fresh-install + Default.zip auto-activation and package switching still need
  a clean end-to-end run on the emulator with the old `/rime` absent.

---

## 12. Session 2026-08-18: IME package delete button

Because Rime data is now app-managed and users cannot browse the package
library directly, the IME package picker now supports deleting non-default
packages.

- `ImePackageManager.DEFAULT_PACKAGE_FILE_NAME` centralizes the app-shipped
  package name (`Default.zip`).
- `ImePackageManager.deletePackage(fileName)` deletes a non-default,
  non-active package zip plus any extracted state dir.
- `ImePickerDialog` uses a custom list adapter: each package row shows the
  package name (active marked with ✓) and a delete icon for every package
  except `Default.zip`.
- Deleting the currently active non-default package is blocked with a toast:
  switch to another package first.
- Added `ime_package_deleted` / `cannot_delete_active_ime_package` strings
  (en, zh-rCN, zh-rTW).
- `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`
  pass.

---

## 13. Session 2026-08-18: remove IME package entry from Keyboard Style settings

IME package management now lives only in the top-level **IME packages** screen.
The legacy `selectedIme` preference was still auto-registered in `ThemePrefs`,
which caused an “IME packages” row to appear under Keyboard Style settings.

- Removed `ThemePrefs.selectedIme` and its `SELECTED_IME` preference key.
- Removed the now-dead `selectedIme` writes from `ImePackageManager` and
  `ImePickerDialog`; the active package remains tracked by
  `ImePackageManager`’s `active-manifest.yaml`.
- Keyboard Style now only shows color/normal-mode/day-night preferences.
- `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`
  pass.

---

## 14. Session 2026-08-18: runtime color changes rebuild views instead of restyling

Incremental restyle was the wrong approach for runtime color changes: many UI
classes cache `ColorManager` results (`Key`, `CandidateItemUi`, preedit,
toolbar buttons, liquid tabs, etc.), so updating a few views left stale colors
elsewhere.

- `ColorManager` change notifications now call `replaceInputViews()` (full
  rebuild) instead of `InputView.restyle()` / `CandidatesView.restyle()`.
- Rebuilding `InputView` creates a fresh `InputDependencyManager`/DI graph, so
  all delegates and their cached views are recreated with the new palette.
- This matches the pre-incremental-restyle behavior that worked correctly.
- Verified on emulator: runtime night-mode toggle changes the keyboard palette
  from light to dark.
- `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`
  pass.

---

## 15. Session 2026-08-18: legacy cleanup

Removed the obsolete middle-ground/legacy code that is no longer reachable
after the self-contained IME package model became the only model.

### Removed Kotlin runtime code

- `ThemeFilesManager`, `ThemeItem`
- `StandardCatalog`, `ThemeResolver`
- `SchemaLayoutPackageManager`, `SchemaLayoutPackageInstaller`,
  `SchemaLayoutManifest`
- Standard-catalog support from `ComponentManifest`/`ComponentResolver`/
  `ComponentValidator` (the `standard` magic reference and
  `use_standard_preset_keys`/`standard_keyboards`/`standard_color_schemes`)
- `ComponentSource.fallback`

### Simplified remaining code

- `SchemaLayoutRegistry` was renamed to `DefaultKeyboardRegistry`; it now only
  carries the active package’s explicit schema → default-keyboard binding.
- `ComponentThemeLoader.loadTheme()` no longer takes a standard catalog or
  fallback root; packages are fully self-contained.
- `DefinitionValidator` validates component manifests only.
- The in-app “Validate definition file” action validates component manifests
  only.

### Removed legacy sample/script files

- `sample_theme_schemas/standard/`, `sample_theme_schemas/shared-aux/`,
  `sample_theme_schemas/minimal-14jian/`
- Legacy monoliths `tongwenfeng.trime.yaml`, `简纯+14键.trime.yaml`,
  `14jian.schema.yaml`
- Obsolete migration/reference scripts:
  `extract_shared_aux.py`, `build_jian_component.py`,
  `split_legacy_theme.py`, `test_equivalence.py`
- Updated `validate-definitions.py` and `package_schema.py` to the
  self-contained component model only.

### Resolved earlier open items

- End-to-end clean-device verification is done.
- Candidate width/text-size issue is fixed through the 14键 package style
  values.

---

## 16. Session 2026-08-18/19: code review hardening (activation, validation, recovery)

Multiple review rounds produced fixes that are now committed on
`refactor/untangle-ime-engine`.

- `ImePackageManager.activate()` is transactional:
  - validates the new package in a staging dir first,
  - backs up the active package/Rime files/legacy schemas/build dir,
  - rolls back on failure (files + `default.custom.yaml` + Rime schema
    selection),
  - cleans stale `.staging-*` / `.backup-*` dirs on startup.
- Activation is single-flight (`AtomicBoolean` + lock) and
  `ensureDefaultPackageReady()` is serialized with a `Mutex`; startup will not
  override a concurrently installed custom package.
- `restoreActiveTheme()` self-heals stale active manifests, runs file I/O off
  the main thread, and only catches expected theme-load exceptions.
- `ComponentValidator` requires non-empty `style` and `preset_color_schemes`;
  Python `validate-definitions.py` mirrors this.
- `ColorManager`/validators detect fallback-color cycles; builtin fallback keys
  are shared via `BuiltinFallbackColors.kt`.
- Kotlin/Python resolver parity: string component specs and strict palette
  errors are aligned.
- In-app definition validation for `content://` URIs falls back to a clearly
  labeled syntax-only result when sibling files are unavailable.
- Added `-dontwarn java.beans.**` to `app/proguard-rules.pro` so release builds
  succeed with SnakeYAML (R8 would otherwise fail on missing `java.beans`).

---

## 17. Session 2026-08-18: YAML attribute reference + keyboard previewer

Added `tools/trime-package-previewer/`:

- `index.html` — a standalone HTML app with:
  - categorized documentation of every YAML attribute the Trime UI consumes
    (package/manifest, keyboards, key behaviors, style, preedit, window,
    toolbar, colors, liquid keyboard),
  - a package loader (folder or zip) that resolves component composition,
    color palettes/fallbacks, `__include`, and renders a keyboard preview,
  - light/dark palette switch, keyboard/scheme selectors, clickable keys with
    pressed highlight, and typed-text output in the candidate area.
- `README.md` — usage notes (CDN dependencies: js-yaml, JSZip).

Also renamed the default package display name from `tongwenfeng` to `同文风`
in both `app/src/main/assets/shared/Default/manifest.yaml` and
`sample_theme_schemas/tongwenfeng/manifest.yaml`.

---

## 18. Session 2026-08-19: IME picker click-through fix

The package picker’s non-default rows (which contain a delete `ImageButton`)
were not receiving ListView item clicks because the button was focusable and
stole focus. Fixed in `ImePickerDialog`:

- Delete `ImageButton` is now `isFocusable = false` /
  `isFocusableInTouchMode = false`; it remains clickable via touch.
- Selecting an already-active package now shows a toast
  (`ime_package_already_active`, en/zh-rCN/zh-rTW) instead of silently closing.
- `isActivePackage()` guards against missing files.
- Added Timber logs on click/activation for future debugging.

Verified on emulator: clicking 简纯+14键 now opens the deploy dialog and
activates successfully (large 14jian package can take 1–2 minutes to deploy).

---

## 19. Session 2026-08-19: 14jian symbol keyboard menu height

The split `sample_theme_schemas/简纯+14键/keyboard.yaml` had the symbol/emoji
bottom menu rows at `height: 10`, while the user’s real legacy YAML used
`conf/menu_height: 30`. This caused bottom-row labels to be clipped in the new
package model.

- Restored all bottom menu rows to `height: 30`.
- Refactored the 456 duplicated menu-row blocks into YAML anchors/merge keys:
  - `x-menu-styles.menu` / `menu-hl` / `ywz-menu` / `ywz-menu-hl`
  - each bottom key is now `- <<: *menu` + `click: ...`
- `sample_theme_schemas/rime.雾凇/build/简纯+14键.trime.yaml` (ignored source)
  was also corrected from `menu_height: 10` to `30`.
- Rebuilt `sample_theme_schemas/简纯+14键.zip`; verified on emulator that the
  30-height menu rows render correctly.

---

## 20. Repo state / untracked files

Tracked work is committed on `refactor/untangle-ime-engine`. The following are
intentionally **not tracked**:

- `CODE_REVIEW-aa14308a.md`, `CODE_REVIEW-fix-da120a30.md` — review docs.
- `sample_theme_schemas/简纯+14键.trime.yaml` — user-provided legacy reference.
- `sample_theme_schemas/rime.雾凇/` — ignored Rime/sample source tree.
- Generated `*.zip` packages (`Default.zip`, `简纯+14键.zip`) are build
  artifacts and are not tracked.
