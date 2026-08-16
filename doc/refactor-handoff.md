# Session Handoff — Trime Refactor (untangle engine / definitions / theme)

**Last updated:** 2026-08-15 · **Branch:** `refactor/untangle-ime-engine`
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
8. **Plugin install ATTEMPTED (NOT completed — see §5)**: `dsh-routing-suite`
   (injector + router-standard preset). Blocked by sandbox read-only `~/.dsh`;
   the escalation was rejected. Ready to finish in the next session.
9. **Phase 0.5 DONE**: replaced the stale `GeneralStyleTest` and added a pure
   JVM Kotest batch (`KeyActionDefinition.parse`, `KeyActionCommand.fromName`,
   `Theme.decode` + `__include`, `RimeMessage.nativeCreate`, `RimeUiState`).
   To make `KeyActionDefinition.parse` JVM-testable, introduced
   `KeyLabelProvider` (Android + pure ASCII provider) and made `KeyCode`
   prefer the generated Rime mapping before the Android fallback. Targeted
   `testDebugUnitTest` passes (21 tests).
10. **Phase 2 item 8 (core) DONE**: added app-shipped standard catalog files
    (`assets/shared/standard/{preset_keys,keyboards,colors}.yaml`), a
    `StandardCatalog` loader, and `ThemeResolver` that merges the catalog under
    each theme (theme overrides win). `trime.yaml` is now decoration-only;
    legacy monoliths (e.g. `tongwenfeng`) remain loadable as overrides. Schema
    tier still lands with item 12.
11. **Phase 2 item 9 DONE**: made tier-1 selection explicit-by-name via
    `use_standard_preset_keys: true`, `standard_keyboards: [...]`, and
    `standard_color_schemes: [...]`; unknown declared names fail validation;
    selecting a keyboard pulls in its `__include` dependencies. `__include`
    now fails loudly on unknown targets, and the legacy `import_preset` theme
    alias is retired (the field was removed from `TextKeyboard`).
12. **Schema-tier reference pair + manifest model**: created
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
13. **Phase 3 item 19 DONE**: color schemes are now self-contained light/dark
    pairs (`light:`/`dark:` blocks). Legacy flat schemes still work and use
    their light palette as the dark fallback. `ColorManager`'s 4-branch
    `light_scheme`/`dark_scheme` logic is removed; night mode just selects the
    scheme's `darkColors`.
    **Standard resource delivery**: `standard/colors.yaml` and
    `tongwenfeng.trime.yaml` have been converted to the new `light:` shape
    (dark fallback = light). Legacy flat schemes are still accepted for
    compatibility.
14. **Phase 3 item 18 DONE**: `ColorManager` now merges the builtin fallback
    table with theme `fallback_colors` once per theme switch and resolves from a
    single combined map; duplicated builtin fallback comments were removed from
    shipped theme files.
15. **Definition spec + standard colors migration DONE**: added
    `doc/definition-schema.md` and updated `doc/trime-schema.json` for the
    three-tier YAML shapes (standard catalog, decoration theme, schema-layout
    package manifest/fragment). Converted `standard/colors.yaml` to the new
    self-contained `light:` shape.
16. **Phase 2 item 14 DONE**: `DefinitionValidator` core, in-app “Validate
    definition file” action, and `script/validate-definitions.py` CLI (with
    `--check-shipped` for CI).
17. **Phase 3 item 20 DONE**: removed `@Parcelize`/`Parcelable` from `Theme`
    and all theme model classes; they are now plain immutable JVM-friendly
    models.
18. **Phase 3 item 15 DONE**: added `ThemeColor` enum and migrated all static
    color/drawable string literals to typed enum calls.
19. **Phase 3 item 17 DONE**: `ColorManager` precomputes a resolved palette on
    scheme/light-dark switches and reads from it in `getColor`/`getDrawable`.
20. **Phase 3 item 16 DONE**: added `ThemeContext` facade over
    `ThemeManager`/`ColorManager`/`FontManager`/`KeyActionManager`.
21. **Automated app testing**: added `doc/automated-testing.md` and a
    compiling `SmokeTest` androidTest that launches `MainActivity` and checks
    the default theme loads. Running `connectedDebugAndroidTest` still needs an
    emulator/device.
22. **Phase 3 item 21 DONE**: added `BaseInputView.restyle()` and overrides;
    color changes now restyle existing input/candidate views in place. Theme
    structure changes still use full rebuild as a safe fallback.
23. **Canonical install workflow recorded**: user picks a schema-layout zip →
    extract + merge with standard → copy package resources to user backgrounds
    → deploy Rime schema → add to `default.custom.yaml` as default → activate.
    Legacy monolithic theme files are not part of this workflow.
24. **Full `简纯+14键` conversion DONE**: added
    `sample_theme_schemas/简纯+14键/` — a tier-2 `theme.yaml` (decoration +
    general keyboards/keys/colors as light/dark pairs) plus a tier-3 package
    (`manifest.yaml`, full `14jian.schema.yaml`, self-contained
    `14jian.layout.yaml`). The conversion is generated by
    `script/split_legacy_theme.py`, which expands the legacy
    `conf`/`styl`/`__patch` indirection into explicit fields; all reference
    definitions pass `script/validate-definitions.py --check-shipped`.
25. **Component model design started**: `doc/component-model.md` proposes
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
26. **Component model reference implementation DONE**: Python prototype +
    Kotlin resolver (`data/theme/component/`) + `DefinitionValidator`
    component-manifest support + JSON schema + shared-aux extraction +
    thin tongwenfeng and 简纯+14键 component manifests. Full
    `testDebugUnitTest` passes.
27. **Runtime component theme loading DONE**: `ComponentThemeLoader` resolves
    a component manifest into a runtime `Theme`; `ThemeFilesManager` discovers
    component themes (`<id>.component.yaml` or `<id>/component.yaml|manifest.yaml`)
    alongside monolithic `*.trime.yaml`; `ThemeManager` tries the component
    manifest before the legacy deployed file. Unit tests cover the loader.

---

## 8. Plugin install: dsh-routing-suite (pending)

The user asked to install https://github.com/yjh051108/dsh-routing-suite
(an "injector × reasoning-mode router" kit for DSH: runtime injector +
task-aware router presets). **Not completed** — blocked by the sandbox:
`~/.dsh` is read-only in workspace mode, and the user rejected the
`danger-full-access` escalation for `dsh plugin --profile web add`.

State on disk (in the workspace, all untracked):
- `./.dsh-routing-suite/` — cloned with all 3 submodules
  (injector@v0.3.3, preset/router-standard@v0.3.0, mode-boost@v0.1.0)
- `./.npm-global/` + `./.npm-cache/` — local pnpm install
  (`~/.npm` cache and home prefix are read-only; pnpm lives at
  `.npm-global/bin/pnpm`)

To finish (next session, needs user approval for `~/.dsh` writes, or run from
the user's own shell):

```bash
# 1. injector (official assembly path)
PATH="/home/jin/Sources/my_projects/trime/.npm-global/bin:$PATH" \
  dsh plugin --profile web add /home/jin/Sources/my_projects/trime/.dsh-routing-suite/injector

# 2. router-standard preset (user-level roster)
mkdir -p ~/.dsh/.agent-presets
cp -r .dsh-routing-suite/preset/preset/router-standard ~/.dsh/.agent-presets/router-standard
# (also: router-spec is available; mode-boost is a third component)

# 3. restart DSH → new session picks Router Standard (experimental)
```

Notes: `dsh plugin --profile web add <dir>` needs pnpm on PATH (step uses the
local one). The preset loader caches ESM by URL — do NOT overwrite an
installed preset in place; use fresh names. The user ALSO wants to change the
agent preset (likely to router-standard / router-spec) — that happens once
the preset lands in `~/.dsh/.agent-presets/`.

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
   - **Automatic app testing**: `doc/automated-testing.md` + compiling
     `SmokeTest` androidTest; running requires an emulator/device.
   - Commits on this branch: c5e1dd6a (full 14jian split) -> 9b900353
     (mode-boost cleanup) -> 89b05b02 (mode-boost notes) -> a751cf2f (dsh
     install complete) -> 5d333858 (docs refresh) -> d3ce11c6 (handoff update)
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
     d5c46822 (docs).
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
