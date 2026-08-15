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
3. Current state: analysis + plan COMPLETE. No implementation started yet.
4. Next step (awaiting user choice when this session ended):
   - (a) start Phase 0 item 1 (type message protocol) — recommended first
   - (b) start Phase 2 item 8 (tier split) — the user's core ask
   - (c) prototype the standard catalog YAML files (extract from
     trime.yaml/tongwenfeng.trime.yaml) as a reviewable proposal
   - (d) user may raise something else
5. Commit docs to the branch so they survive: `git add doc/refactor-plan.md
   doc/refactor-handoff.md && git commit -m "docs: refactor plan and session
   handoff"` (or leave uncommitted — user preference; currently uncommitted).
