# Refactor Plan: Untangle Rime Engine, IME Definitions, and Theme/Color Handling

Branch: `refactor/untangle-ime-engine` (from `develop` @ `6c3b2bce`)

## 0. Guiding decision

- **Baseline: the fork's current state only.** Upstream (`osfans/trime`) changes
  are out of scope for now — we do not know its direction, and we will not
  design against speculative upstream refactors.
- Upstream syncs (merge/rebase) remain possible at any point and are treated as
  **mechanical** steps. A cleaner internal architecture makes those syncs less
  painful, but they do not constrain this design.
- Where a choice is ambiguous, prefer whatever is best for the fork's own
  maintainability and the "definitions easier to use" goal.

## 1. Verdict on the hypotheses

Both of the user's comments are **valid**, with nuance.

### H1: "The Rime engine and the Android part are tangled" — TRUE, but the JNI seam is clean

The C++/JNI boundary itself is well encapsulated: `RimeApi` is a clean suspend
interface, `RimeProto` is pure data, and `RimeSession` is a decent client
abstraction with correct refcounting. The tangle lives *above* that seam:

- **`Rime.kt` (519 lines) mixes five concerns**: JNI calls, response synthesis,
  cache maintenance, **UI-preference reads** (`AppPrefs` for inline-preedit mode
  and ascii-switch tips, `Rime.kt:69-70`), and **Android side effects**
  (`DataManager.sync()`, `OpenCCDictManager.buildOpenCCDict()` on deploy,
  `appContext.isStorageAvailable()`).
- **Presentation logic lives inside the engine**: `handlePreedit` decides inline
  vs. composing rendering (`Rime.kt:258-276`) and `showAsciiSwitchTips`
  fabricates fake `CompositionMessage`s (`Rime.kt:332-348`). The engine imports
  `InlinePreeditMode` — an `ime.core` UI enum — directly.
- **The UI reaches into engine internals**: 20+ `rime.run { statusCached /
  schemaCached / ... }` pull-sites across `Key.kt`, `KeyAction.kt`, `KeyView.kt`,
  `KeyboardWindow.kt`, `InputView.kt`, `LiquidWindow.kt`, `SwitchOptionWindow.kt`
  etc. These caches are written on the rime thread and read on the main thread
  **with no synchronization** (no `@Volatile` anywhere) — a latent data race.
- **Worst offender**: `KeyView.drawSymbol` calls
  `rime.run { !getRuntimeOption("_hide_key_symbol") }` — a `runBlocking` Rime
  query — **inside `onDraw`** (`KeyView.kt:382-383`).
- **Leaf views grab the daemon singleton** with
  `RimeDaemon.getFirstSessionOrNull()!!` (`Key.kt:21`, `KeyAction.kt:72`,
  `KeyView.kt:45`) — non-null-asserted global reach, bypassing session scoping.
- **The message protocol is weakly typed at the seam**: C++ sends ints 1-3 with
  a single string; Kotlin synthesizes ints 4-10 (`Rime.kt:246-255, 229-232`)
  mapped to `MessageType.ordinal()` (`RimeMessage.kt:136-139`). Wrong type or
  index = runtime `ClassCastException`/`IndexOutOfBoundsException`.
- **`RimeDaemon.kt` mixes engine lifecycle with Android notifications**
  (`RimeDaemon.kt:117-127, 129-163`) and its `run()` uses `runBlocking`
  (`RimeDaemon.kt:65`).
- **Theme loading is routed through the engine**: `ThemeManager.loadThemeById`
  calls the static JNI `Rime.deployRimeConfigFile` (`ThemeManager.kt:62`).

Nuance: the codebase is *mid-refactor* — there is already a Kodein DI container
(`InputDependencyManager`), an event bus (`InputBroadcaster`), an
`EventStateMachine`, and recent commits ("decouple ToolButton from RimeDaemon",
"merge Rime JNI queries") show this untangling is the right direction.

### H2: "Theme/color definition is overly complicated" — TRUE

- **73-78 ad-hoc `ColorManager.getColor/getDrawable` call sites** across ~25 UI
  files, all keyed by **string literals** (zero compile-time safety). `Key.kt`
  re-implements fallback logic per-property with 18 `by lazy` resolutions.
- **Three parallel fallback tables**: active color scheme → per-theme
  `fallback_colors` → hardcoded 40-entry `BuiltinFallbackColors`
  (`ColorManager.kt:52-94, 205-229`). The builtin table is *also* duplicated as
  a comment in `trime.yaml` (lines ~96-122) — guaranteed doc drift.
- **Night mode is a 4-branch `when`** driven by magic `light_scheme` /
  `dark_scheme` keys stored *inside* the color map (`ColorManager.kt:138-163`).
- **Four mutable global singletons** (`ThemeManager`, `ColorManager`,
  `FontManager`, `KeyActionManager`) requiring order-sensitive manual resets on
  every theme switch (`ThemeManager.kt:112-115, 127-130`).
- **Propagation = full rebuild**: a theme or color change tears down and
  re-creates both `InputView` and `CandidatesView`
  (`TrimeInputMethodService.kt:104-128, 271-293`); three overlapping rebuild
  triggers (theme, color, prefs).
- **`@Parcelize Theme` couples YAML parsing to Android Parcelable**, with `!!`
  assertions that crash on malformed YAML (`Theme.kt:38, 45, 49, 56, 61`).
- **Definitions are giant flat `data class`es with hand-written `decode()`
  defaults** (`GeneralStyle` ~50 fields, `TextKeyboard`/`TextKey` similar) —
  every new theme parameter is a field + decode line + default, and there's no
  schema-driven validation.

### H3: "IME definition is overly complicated" — TRUE, and the layout/color mixing makes it worse

- **`KeyAction` fuses parsing, interpretation, and rendering** in one class: its
  `init` block parses 3 token forms (plain string, `{commit:...}` inline,
  preset-key lookup), resolves against `ThemeManager.activeTheme.presetKeys`,
  parses keycodes from strings, then `getLabel/getText/getPreview` do
  shift/ascii/state-aware rendering — reaching into live Rime state and
  `AppPrefs` (`KeyAction.kt:131-202`).
- **Commands are stringly-typed with silent no-op fallbacks**:
  `when (action.command)` with ~15 string literals
  (`CommonKeyboardActionListener.kt:178-193`); option names matched by string
  prefixes `"_keyboard_"`/`"_key_"` (`KeyboardWindow.kt:290-309`);
  keycode→name→Rime-value string round-trip.
- **`KeyBehavior` conflates gestures with state conditions** (COMPOSING,
  HAS_MENU, PAGING are states; SWIPE_*, LONG_CLICK are gestures).
- **`__include` inheritance is declared but unimplemented**: `trime.yaml:1051,
  1508` use `__include: /preset_keyboards/default` (etc.), but nothing in Kotlin
  reads that key — `TextKeyboard.decode` silently drops it, so those keyboards
  do NOT inherit. The only working inheritance is `import_preset`
  (`TextKeyboard.kt:157`, `KeyboardWindow.kt:101-103`). Theme authors following
  the `__include` convention get silently wrong layouts.
- **`KeyboardWindow` mixes board-switch policy with engine reads**:
  smart keyboard matching by schema alphabet, ascii-mode policy, landscape
  fallback (`KeyboardWindow.kt:148-196, 236-258`).
- The deprecated `KeyboardSwitcher` global still mediates dispatch
  (`Key.kt`/`CommonKeyboardActionListener`).
- **Themes bundle layouts and colors in one monolith** (see §2): a 4000-line
  file mixes 50 keyboards with color schemes, so changing one layout means
  hunting through unrelated content, and every theme re-ships its own copy of
  the standard layouts.

## 2. Target architecture

### 2.1 Runtime layers (unchanged by the definition split)

```
┌─────────────────────────────────────────────────────────────┐
│  engine (pure Kotlin, no Android imports)                   │
│  core/ : JNI wrappers, RimeApi, RimeMessage (typed),        │
│          RimeUiState (immutable StateFlow), no prefs/UI     │
├─────────────────────────────────────────────────────────────┤
│  daemon/ : lifecycle + session refcounting only             │
│  (notifications moved out to ui/deploy)                     │
├─────────────────────────────────────────────────────────────┤
│  definitions (pure model + parser, no Android/Rime deps)    │
│  theme model: immutable, schema-validated, typed color keys │
│  keyboard model: Action/ActionParser separated from runtime │
├─────────────────────────────────────────────────────────────┤
│  ime/ (Android UI)                                          │
│  service owns: RimeSession, RimeUiState, action bus         │
│  views observe state; inject actions via DI;                │
│  one ThemeContext object, apply-in-place restyle            │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 The composable definition model (the core change)

The theme monolith is split into **three tiers**. The runtime `Theme` object the
views see today keeps the same merged shape — this is a *definition-layer*
change, not a UI rewrite. At load time a `ThemeResolver` merges the tiers into
one model.

| Tier | What it contains | Who owns it | Changes how often |
|---|---|---|---|
| **1. Standard catalog** | Standard keyboards (qwerty, letter, number, symbols, editor, emoji, liquid), standard color schemes, `preset_keys` | **The app** (ships with Trime) | Rarely — versioned, changelogged |
| **2. Decoration** | Theme color schemes (overrides + new), `fallback_colors`, backgrounds/images, fonts, chrome (`preedit`/`window`/`tool_bar`), **entire `generalStyle`** (metrics + fonts + colors) | **Theme author** | Per-theme |
| **3. Schema layouts** | Non-standard keyboards bound to a specific Rime input method (T9, 14-key, cangjie, …) | **Schema author** — travels with the schema | With the schema |

**Why this split fixes the current pain:**

- *Layout vs. color*: a theme file becomes decoration-only. Designers change
  colors without touching layouts and vice versa.
- *Standard layouts*: one shared, app-shipped source of truth. A fix to the
  standard qwerty layout propagates to every theme automatically. Themes no
  longer re-ship 50 keyboards.
- *Schema layouts*: a T9 or 14-key layout ships with its Rime definition, so
  installing the schema brings its keyboards — and a standard 26-key theme
  never accidentally offers a T9 layout.

**Current evidence that motivates this split:**

- `tongwenfeng.trime.yaml` = 3949 lines: `preset_color_schemes` 154–861,
  `preset_keyboards` 1061–3949 (50 keyboards). `trime.yaml` = 1716 lines:
  colors 130–676, keyboards 965–1716.
- The only schema↔layout binding is the implicit naming convention in
  `smartMatchKeyboard` (`KeyboardWindow.kt:148-163`): a theme that happens to
  contain a keyboard named like the schema id uses it, else guess from the
  alphabet. T9/14-key definitions and their layouts are inseparable *in
  practice* but the coupling is nowhere declared.

### 2.3 Standard catalog — declaration, discoverability, validation (decided)

Per the user's direction: standard keyboards all live in **one single YAML
file**, and predefined colors likewise in **one single file** (they are rarely
touched). The declaration/validation contract:

**D1 — Declaration is always explicit.** A theme or schema layout that wants to
use a standard component must reference it **by name** (`qwerty`, `26key`,
`symbol`, `emoji`, …). No implicit-by-name fallback: the standard catalog is
not silently merged into every theme. References are resolved and **validated
at load time** (missing target = clear error, not silent drop — this is also
the fix for the `__include` gap).

**D2 — Discoverability & validation via a validating tool.** The hard part. A
designer writing a custom layout has no way to know what standard components
exist or whether their references are correct. Decision: provide a
**validator** that checks a customized layout against the standardized catalog —
either **in-app** (a "validate theme/layout" action in settings) or as a
**CLI tool** (scripts/, runnable in CI). It catches errors early: unknown
standard references, malformed key actions, invalid color keys, `__include`
cycles, missing required fields. A generated **reference catalog document**
(still worthwhile) feeds the validator and the docs.

**D3 — Versioning is low priority.** The keyboard-layout *parsing* format is
unlikely to change, so a strict version/changelog contract is unnecessary.
Keep `config_version` as-is; no min-version declaration machinery. (If a
breaking format change ever happens, it can be handled ad-hoc.)

## 3. Phased plan (each phase independently shippable)

### Phase 0 — Foundations (no behavior change)

1. **Type the message protocol** (high impact, low-medium effort)
   - Replace int literals in `Rime.kt` with `RimeMessage.create(MessageType.X,
     ...)` or direct sealed-instance emission.
   - Keep one thin `fromNative(type, raw)` adapter for the C++ channel (1/2/3).
   - Wrong-type/out-of-range failures become compile-time.
2. **Extract prefs + presentation out of `Rime`** (high impact, medium effort)
   - Inject an `InputOptions` interface (inlinePreeditMode, asciiSwitchTips,
     pagingMode, nullInputType) instead of reading `AppPrefs` directly.
   - Engine emits raw `CompositionMessage`; the UI decides inline rendering.
   - Move `showAsciiSwitchTips` fabrication to the UI layer.
   - Result: `core/` becomes Android-free.
3. **Decouple data-dir/OpenCC wiring** (low-medium impact, low effort)
   - Inject `DataDirProvider` and a deploy-start callback so engine startup
     takes paths as parameters; drop `OpenCCDictManager` / `appContext` deps.

### Phase 1 — Engine boundary hardening

4. **Split `RimeDaemon`** (medium-high impact, low effort)
   - Keep refcount/lifecycle; move notification/`LogActivity` code to a
     `DeployNotifier` collecting `messageFlow` on its own scope.
5. **Remove `getFirstSessionOrNull()!!` from views** (medium impact, low effort)
   - `InputDependencyManager` already binds `rime: RimeSession` — inject via DI
     into `Key`, `KeyAction`, `KeyView` instead of the daemon global.
6. **Replace pull-based caches with an observable `RimeUiState`** (medium
   impact, medium effort) — **DONE**
   - `RimeApi.uiState: StateFlow<RimeUiState>` + `RimeSession.uiState`;
     engine updates it atomically in `handleRimeMessage`/`setRuntimeOption`.
   - Hot-path consumers migrated from `rime.run { statusCached }` (runBlocking)
     to `rime.uiState.value`: `Key`, `KeyView`, `InputView`,
     `TrimeInputMethodService`, `KeyboardWindow`, `CommonKeyboardActionListener`,
     `CandidatesView`, `LiquidWindow`.
   - Remaining: `KeyAction` (needs item 10), `schemaCached` JNI reads in
     `KeyboardWindow`/`SwitchOptionWindow` (rare, dialog-time), switch-dialog
     `getRuntimeOption` calls.
7. **Fix silent-loss buffering and `runBlocking`** (low impact, low effort)
   - Use a `Channel`/suspending emit or conflation policy for `messageFlow`
     (currently `DROP_OLDEST` with buffer 15 — messages lost under fast typing).
   - Make `RimeSession.run` suspend or explicitly scoped.

### Phase 2 — Composable definition model (layouts + schema binding)

8. **Split the theme monolith into the three tiers** (high impact, medium
   effort) — **DONE for tiers 1+2 core; schema tier lands with item 12**
   - Extract standard keyboards + predefined colors into the **standard
     catalog** files (single YAML each, app-shipped).
   - Leave theme files as decoration (including the entire `generalStyle`);
     schema layouts move to the schema tier.
   - Introduce `ThemeResolver`: merge tiers at load into the same `Theme` shape
     views use today. Migration: keep legacy monolithic themes loadable by
     treating their embedded keyboards/colors as overrides.
9. **Implement explicit declaration + validation** (high impact, medium
   effort) — **DONE**
   - **Explicit-by-name references only** (D1): themes/schema layouts reference
     standard keyboards/colors by name (`qwerty`, `26key`, `symbol`, `emoji`,
     …); no implicit merging. Settled syntax:
     `use_standard_preset_keys: true`,
     `standard_keyboards: [qwerty, …]`,
     `standard_color_schemes: [default, …]`.
   - **`__include` is the single inheritance mechanism** (per the user's
     decision): make it actually work, resolved **at parse time with
     validation** — fixes the silent-drop gap; **retire `import_preset`** and
     migrate its usages to `__include` (the legacy alias is no longer
     recognized; no shipped theme keyboard used it).
10. **Separate parsing from interpretation in `KeyAction`** (high impact,
    medium effort) — **10a DONE, 10b in progress**
    - `KeyActionDefinition` (pure immutable model) + `KeyActionDefinition.parse`
      replaces the init-block parsing; `KeyAction` is now a thin wrapper that
      takes the definition. `KeyActionManager` passes `presetKeys` explicitly
      (no `ThemeManager.activeTheme` hidden global).
    - `getLabel/getText/getPreview/isShiftLock` now take a `RimeUiState`
      snapshot; the `RimeDaemon.getFirstSessionOrNull()!!` global is gone from
      `KeyAction`. **DONE**
    - **10b DONE**: sealed `KeyActionCommand` type replaces the stringly-typed
      `when (action.command)` dispatch; unknown commands fall back to the
      intent handler. (The `_keyboard_`/`_key_` runtime-option prefix matching
      in KeyboardWindow stays — it's an engine option protocol, not a theme
      definition.)
11. **Typed commands instead of strings** (medium impact, low-medium effort)
    - Replace the `when (action.command)` string dispatch with a sealed
      `Command` type; replace `"_keyboard_"`/`"_key_"` prefix matching with typed
      option keys; replace the keycode↔name↔Rime-value string round-trip.
12. **Declared schema↔layout binding** (medium impact, medium effort)
    — **manifest model + registry + switcher binding DONE; zip unpack TODO**
    - Replace the implicit `smartMatchKeyboard` naming convention with an
      explicit declaration. **Pair the schema with its custom layouts** (per
      the user's decision): the Rime schema and its keyboard layouts travel
      together, but as **separate files** — the Rime engine must compile the
      schema file on its own, so they cannot be a single YAML.
    - Reference package: `sample_theme_schemas/minimal-14jian/`
      (`manifest.yaml` + `14jian.schema.yaml` + `14jian.layout.yaml`).
    - Manifest model: `SchemaLayoutManifest` (schema id, files, default
      keyboard, `resources` for images/backgrounds) + `SchemaLayoutRegistry`;
      `KeyboardSwitcher` consults the registry instead of the alphabet
      heuristic.
    - **Delivery: package the pair** — schema + its layouts are shipped
      together as an archive (e.g. a zip: schema file + layout file(s) +
      metadata/manifest). The app unpacks it, compiles the schema through Rime,
      and registers the layouts into the schema tier.
    - Mechanism inside the package: a small manifest maps schema id → layout
      file(s). No alphabet heuristic: unbound schemas use the theme's explicit
      default keyboard.
    - This is what makes tier 3 (schema layouts) work as designed.
13. **Extract keyboard-switch policy from `KeyboardWindow`** (low-medium impact,
    low effort) — **DONE**
    - New DI-bound `KeyboardSwitcher` owns target resolution
      (`.default`/`.next`/`.ascii`/...), explicit schema→layout binding,
      ascii-mode sync,
      the `Keyboard` model cache, and switch state. `KeyboardWindow` only
      renders (views, height flow, caps dispatch, broadcast handling). The
      deprecated global became `KeyboardSwitcherLegacy` (view-level bridge,
      deleted with item 10).
14. **Validator + reference docs** (medium impact, medium effort)
    - Build the **validator** (D2): checks a custom layout/theme against the
      standard catalog — unknown standard references, malformed key actions,
      invalid color keys, `__include` cycles, missing fields. **Delivered
      both ways** (per the user's decision): a shared validation core exposed
      as an **in-app** settings action and as a **CLI tool** (scripts/,
      CI-runnable).
    - Generate the reference catalog document from the standard files; extend
      `doc/trime-schema.json` for editor support; ship a starter theme.

### Phase 3 — Decoration system (colors, backgrounds, chrome)

15. **Typed color keys** (high impact, low effort)
    - Replace string keys with an enum (e.g. `ThemeColor.CANDIDATE_TEXT`),
      generated from the schema; `getColor` takes the enum → compile-time safe.
    - Standard color schemes become enum entries; theme overrides resolve on top.
16. **Single `ThemeContext` object** (high impact, medium effort)
    - Merge `ThemeManager` + `ColorManager` + `FontManager` +
      `KeyActionManager` into one object/container with a single change
      listener; one event → one incremental restyle instead of full rebuild.
17. **Precomputed resolved palette** (high impact, medium effort)
    - Resolve the full color map once per scheme switch (standard base + theme
      overrides); views read from a `ResolvedPalette` instead of resolving
      ad-hoc at draw/construction time.
18. **Unify fallback tables into one source of truth** (medium impact, low
    effort)
    - Single table (schema-driven, standard catalog = base layer) instead of
      three parallel maps; remove the duplicated comment in `trime.yaml`.
19. **Data-driven light/dark scheme pairs** (low-medium impact, low effort)
    - Replace the 4-branch `when` with declared `light_scheme`/`dark_scheme`
      pairs; keep metadata out of the color namespace.
20. **Decouple parsing from Parcelable** (low-medium impact, medium effort)
    - Plain immutable model + separate `decode` mapper with graceful errors
      (no `!!`); Parcelable kept only where truly needed (or dropped in favor
      of a repository lookup).
21. **Incremental UI invalidation** (medium impact, medium effort)
    - Replace `replaceInputViews` full rebuild with per-view `restyle()`
      (colors/fonts update in place); collapse the three rebuild triggers into
      one.

## 4. Suggested order & sequencing

- **Do first** (Phase 0): items 1-3 — pure internal refactor, no behavior
  change, unblocks everything else.
- **Phase 0.5 (tests)**: add the pure-JVM test batch from §6 right after Phase
  0/1 — it locks in the engine boundary refactor while the code is fresh, and
  every later Phase 2/3 change adds tests alongside.
- **Do second** (Phase 1): items 4-7 — makes the engine boundary trustworthy so
  Phase 2/3 can rely on it.
- **Then** Phase 2 (8-14) and Phase 3 (15-21) can proceed in parallel since
  they touch mostly disjoint files (definitions vs. decoration), except items
  6/16 which both touch `TrimeInputMethodService`.
- **Within Phase 2**, item 8 (tier split) must come first; items 9-14 build on
  it. Item 14 (validator/docs) can trail the others — the contract it validates
  is decided by 9/12.
- **Before Phase 2 item 8**, the declaration contract (D1: explicit-by-name) is
  settled; item 8 should define the catalog format with that contract in mind.

## 5. Risks & notes

- `RimeMessage` int↔ordinal coupling must be fixed before touching
  `MessageType` ordering (or add an explicit `type` field).
- `runBlocking` removal may change timing semantics for `KeyView` label
  rendering — keep a cached status snapshot for the draw path.
- Theme rebuild → incremental restyle is the riskiest change (visual
  regressions); do it last, behind a flag if needed.
- **The tier split (item 8) risks breaking theme/schema authors' expectations**
  — keep legacy monolithic themes loadable during migration, and make the
  standard catalog additive (no renames/removals).
- The existing `InputBroadcaster` / DI / `EventStateMachine` are assets — reuse
  them rather than introducing a new architecture.
- `__include` currently does nothing; per D1 it becomes the single explicit
  inheritance mechanism (`import_preset` retired — migrate its usages, e.g.
  `KeyboardWindow.kt:101-103`).

## 6. Unit-testing strategy (new)

The codebase currently has **no meaningful unit tests**: only 2 stale files
(`app/src/test/.../GeneralStyleTest.kt`, `WeakHashSetTest.kt`), the first of
which references `Theme.decodeByConfigId` and `Rime.startupRime` APIs that no
longer exist. Test infrastructure *is* configured (JUnit5 platform + Kotest
runner/assertions in `app/build.gradle.kts`, `testOptions { unitTests { useJUnitPlatform() } }`),
but nothing runs it.

### Why testing is now possible

The refactor so far deliberately created pure, JVM-testable seams:

- **`core/` is Android-free** — `RimeProto`, `RimeMessage`, `KeyValue`,
  `KeyModifier`, `RimeUiState`, `InputOptions`, `RimeEnvironment` are plain
  Kotlin with no `android.*` imports.
- **`KeyActionDefinition.parse(token, presetKeys)`** is a pure function
  (no globals, no engine); `KeyActionCommand.fromName` is a pure mapping.
- **`Theme.decode(Node.Mapping)`** and the `__include` resolver
  (`resolveKeyboardIncludes`) are pure YAML→model functions.
- **`KeyboardSwitcher`** takes injected `context/theme/rime/service`; its
  policy methods (`resolveKeyboard`, `startInputTarget`) are near-pure and
  could be tested with a fake `RimeSession` (see below).

### Test targets (ranked by value)

1. **`KeyActionDefinition.parse`** — token forms (plain key name, preset-key
   lookup, `{Control+a}`, key-sequence braces, inline `{commit,text,label}`),
   fallback label derivation, shift-label computation. Pure; highest value.
2. **`KeyActionCommand.fromName`** — known commands map to sealed types,
   unknown names fall back to `Intent`.
3. **`Theme.decode` + `__include` inheritance** — include resolution, child
   field override precedence, cycle detection (`Circular __include` error),
   missing-base fallback, `import_preset` legacy alias.
4. **`RimeMessage.nativeCreate`** — C++-channel types 1-3 map to
   Schema/Option/Deploy; out-of-range → `UnknownMessage`.
5. **`RimeUiState`** — snapshot immutability, derived accessors
   (`isAsciiMode`, `schemaId`, ...).
6. **`KeyboardSwitcher.resolveKeyboard`** — symbolic targets
   (`.default`/`.next`/`.ascii`/`.last_lock`), explicit schema→layout binding,
   landscape fallback. Needs a `RimeSession` fake — make
   `RimeSession`/`RimeApi` testable (they already are interfaces; a stub
   implementation with a `MutableStateFlow` suffices).

### Test framework decisions

- **Kotest** (already a dependency): `BehaviorSpec`/`StringSpec` style to match
  the existing files.
- **No Robolectric** for now — keep tests JVM-pure; Android-dependent behavior
  stays untested until needed.
- **No JNI in tests** — never call `Rime.startupRime` etc. from unit tests
  (the old `GeneralStyleTest` did; that's why it's broken and why the host
  OOM'd under Gradle). All test targets above avoid the engine.
- **YAML fixtures**: small inline strings or `src/test/resources` files; avoid
  the real `trime.yaml` (1716 lines) in tests.

### When

- **Phase 0.5 DONE**: deleted/replaced the stale `GeneralStyleTest`; added tests
  for targets 1-5 (pure, no engine). These lock in the refactor so far.
- **During Phase 2/3 items**: add tests alongside each definition/theme change
  (parse, inheritance, declaration validation).
- **Validator (item 14)**: its checks are themselves unit-testable — the
  validation core should be a pure function over parsed models.

## 7. Open design questions (for the user)

Settled so far:

- **D1 — Declaration is always explicit**: standard components are referenced
  by name (`qwerty`, `26key`, `symbol`, `emoji`, …); no implicit-by-name
  merging; validated at parse time.
- **D2 — Validator**: a validating tool checks custom layouts/themes against
  the standardized catalog, catching errors early. **Delivered both ways**:
  in-app settings action + CLI tool (CI-runnable), sharing one validation core.
- **D3 — Versioning is low priority**: layout parsing format is unlikely to
  change; keep `config_version`, no min-version machinery.
- **Schema pairing**: Rime schema and its custom layouts travel together but as
  **separate files** (Rime must compile the schema file independently),
  **delivered as an archive** (zip: schema + layouts + manifest) that the app
  unpacks and registers.
- **Inheritance syntax**: consolidate on **`__include`**; retire
  `import_preset`.
- **`generalStyle` stays entirely in the decoration tier.**

Still open (minor, can be decided during implementation):

1. **Zip package layout**: exact file layout inside the archive (schema file,
   layout file(s), manifest format).
2. **Validator scope**: which checks ship first (references, key actions, color
   keys, `__include` cycles)?
