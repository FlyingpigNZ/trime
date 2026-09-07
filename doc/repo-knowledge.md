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
- Don't touch the `librime*` submodules; consume them read-only. Prefer the
  public `rime_api.h` C surface; librime's shipped C++ headers (`src/rime/*.h`)
  may be wrapped in `librime_jni/` where the public C surface does not expose
  the needed state (no C ABI guarantee — keep such use small and isolated).
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

### 4.1 Per-schema `.extended.yaml` (implemented)

`.schema.yaml` is owned by the Rime engine (it compiles the input method), so the
app must **not** add application-specific sections to it. Each schema may ship a
sibling application-level extension file, named after the schema id, stored under
`rime/` (the prefix is stripped at install time, so it lands in the workspace
root):

```text
rime/wanxiang_flypy_t9.extended.yaml
rime/wanxiang_t9.extended.yaml
```

It carries (a) the per-schema `tool_bar` override (`__replace: false`/`true`,
default merge), (b) `t9_disambiguation` (`enabled`, `input_method`, the
pinyin syllable table and the 双拼 key mapping) and, for the same section in
`wanxiang_14jian.extended.yaml`, per-syllable `flypy_14_code` (the `/14jian`
fold of each `flypy_code`). The app parses it with `Yaml`; the Rime engine
never touches it.

Kotlin wiring:
- `data/theme/SchemaExtension.kt` — the file model (`tool_bar`,
  `t9_disambiguation`, syllables, flypy keys).
- `data/theme/SchemaExtensionResolver.kt` — loads `<schemaId>.extended.yaml`
  from `PackageStore.activeWorkspaceDir()`; computes the effective toolbar
  (replace or node-level deep merge, same semantics as
  `ComponentResolver.mergeMappings`).
- `ThemeManager.applySchemaToolBar(schemaId)` — applies/restores the per-schema
  toolbar on schema switch (called from
  `KeyboardWindow.onRimeSchemaUpdated`); keeps `baseToolBar` so switching away
  from an overridden schema restores the package toolbar.
- `ime/disambiguation/` — `PinyinDisambiguationDecoder` (key-code string →
  legal pinyin sequences; full-pinyin DP over digits, 双拼 two-character
  grouping for T9 digits (`flypy`) and for the 14-key letters (`flypy14`)),
  `PinyinDisambiguationPanel` (scrollable strip over the keyboard — a column
  over T9's first punctuation column, or a row over the 14-key keyboards'
  first digit row — intercepts touches), `PinyinDisambiguationController`
  (drives the panel from the owned key-code string, feeds the picked pinyin /
  双拼 code back via `setInput` / `clearAndSetInput` (mixed composition),
  Backspace rolls the last confirmation back). The「键盘样式」`pinyin_filter`
  master switch (default on) gates both flows.
- Naming decision (2026-09): the Kotlin types/files use the generic
  `PinyinDisambiguation*` names (`PinyinDisambiguationController/Panel/Adapter/
  Decoder`, field `KeyboardWindow.pinyinDisambiguation`); the **data** layer
  deliberately keeps its T9 name — yaml key `t9_disambiguation` and
  `SchemaExtension.T9Disambiguation` — because the feature now also serves
  14-key but renaming the data key would break shipped packages/data (decided:
  no rename; would need a migration if ever revisited).
- Gotcha: a package's `manifest.default_keyboard` binding wins over the
  same-name schema alias in `KeyboardSwitcher.resolveDefaultKeyboard()`, so the
  `wanxiang_14jian` schema actually resolves to keyboard id **`14jian`**; the
  flypy14 extended file must declare `keyboard: 14jian` (an earlier
  `wanxiang_14jian` value never matched and hid the panel).
- Validation rules to keep in mind:
  - `input_method` is canonical-only (`full` / `flypy` / `flypy14`); the loader
    rejects the legacy aliases `quanpin` / `xiaoe` / `xiaoe14` so Kotlin,
    Python and the docs cannot drift again.
  - The generator quotes YAML-risky 双拼 codes (e.g. nuo → `'no'`) in both
    output branches; the validator flags unquoted boolean scalars instead of
    silently skipping the row, so a code table must quote `no`.
- While the panel is showing, the keyboard draws nothing for the covered key
  strip (`Keyboard.pinyinOverlay` = first column for T9 / first row for
  `flypy14`; `KeyView.onDraw` returns early for the covered keys) — neither the
  button backgrounds nor the labels/symbols show through behind the
  transparent panel; the pinyin items render over the plain keyboard backdrop.

Data:
- `script/generate_pinyin_syllables.py` — generates the syllable table
  (pinyin + `t9_code` + `flypy_code` + `flypy_t9_code`) from the built-in
  luna_pinyin dict and the 小鹤双拼 key mapping. Zero-initial syllables are
  two keys like everything else, per the Rime `/base/小鹤双拼` algebra:
  two-letter finals keep their natural spelling (`ai`, `an`, `ao`, `ei`, `en`,
  `ou`), single-letter finals double (`a → aa`), longer finals take a vowel
  guide + final key (`ang → ah`, `eng → eg`).
- `script/extended_validator.py` — validates `<schemaId>.extended.yaml`
  files; wired into `script/validate-definitions.py` (`--check-shipped` and
  zip validation).
- The 万象14键-nogram sample package ships
  `wanxiang_t9.extended.yaml` and `wanxiang_flypy_t9.extended.yaml`.

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

### 5.1 排查提示：奇怪 T9 拼音问题 → 先查"声调数字(7890)泄漏"

万象带调词库用数字 `7/8/9/0` 表 1–4 声（`xlit/①②③④/7890`，26 键带调输入用）。
9 键没有声调通道，但各双拼 base 里 `derive/^(.).+(\d)$/$1$2/` 仍会为每个
音节派生出"声母+调号"码（买 mǎi → `m9`、好 hǎo → `h9`）。若这类派生码漏过
9 键折叠，会折成与真实音节撞码的 2 位数字码，症状是**候选/悬浮拼音出现与
按键明显不符的字，但多数候选正常**：

- `m9` → `69`，撞 `没/妹/每/美(mei→mw)` 与 `某(mou→mz)` → 输入 `69` 混入"买"；
- `h9` → `49`，撞 `够(gou→gz)` → 输入 `2849`(不够) 拼出"不好"(bu+hao)。

处理（在 万象14键-nogram.zip 的 `rime/wanxiang_algebra.yaml` `/9jian` 预设）：
- `xform/^([a-z]{2,})[7890]$/$1/` —— ≥2 字母完整音节剥末尾调号（原有）；
- `xform/^([a-z])[7890]$//` —— 单字母+调号派生码(m9/h9/g0…)整码删除，不可
  剥成裸声母（会制造 1 位拼写）。

覆盖范围：包内只有 `wanxiang_flypy_t9`（`/base/小鹤双拼`+`/9jian`）与
`wanxiang_t9`（仅 `/9jian`）两条 9 键链，`/9jian` 都排在 algebra patch 末尾；
12 份双拼变体里的同类派生码统一被这两条规则清理（含 `;` 辅码形式）。
**新增 9 键方案务必让折叠链以 `/9jian` 收尾**，否则需自带同类清理。
改数据后必须删包重装（prism 重建）才生效。

### How pinyin is rendered/displayed for T9 today

- `super_comment_preedit.lua` `convert_t9_syllable`: for a single digit it
  shows an abbreviation (initial), multi-digit syllables convert to full
  pinyin from the candidate comment.
- The old `t9_preedit.lua` (in `简纯+14键`) is the simple "replace the preedit
  digits with their pinyin/english" filter.
- The user-facing "list of all legal pinyin parses for this key-code string"
  is feature ②, implemented app-side as the disambiguation strip (T9 first
  column / 小鹤双拼14键 first row; see §4.1 and
  `ime/disambiguation/`).

### Rime text/send APIs available to the app

`RimeApi` (`core/RimeApi.kt`): `simulateKeySequence`, `commitComposition`,
`clearComposition`, `getRawInput`, `selectCandidate`, `changeCandidatePage`,
`getCandidates`, `setRuntimeOption`, `getRuntimeOption`, `setInput` (set the
context input directly to feed a mixed composition), `currentSchema`,
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
2. No editing the `librime*` upstream submodules. Consume them read-only:
   prefer the public `rime_api.h` C surface; librime's shipped C++ headers
   (`src/rime/*.h`) may be wrapped in `librime_jni/` when the public surface
   lacks the needed state, kept minimal and isolated (no C ABI guarantee).
3. Data inconsistency → normalize the data, don't loosen the parser.
4. Keep generated assets (zips) consistent with their source schemas.
5. Validate definitions early and fail loudly (schema-first).
6. Do not push unverified/unfinished work to remote.

---

## 9. Dev-environment / infrastructure notes (learned the hard way)

Operational facts about the DSH dev container this repo is built in. These are
**environment quirks, not repo bugs** — they bit us once; record them so they
are not rediscovered the hard way.

### 9.1 DSH process sandbox (workspace-write) needs a usable backend

- The DSH bash tool refuses to run **any** command under `workspace-write` /
  `read-only` when no sandbox backend is usable:
  `sandbox mode "workspace-write" is requested but no sandbox backend is usable on this host`.
  This is fail-closed by design (`@deepseek-ai/dsh-sandbox-local`), not a bug.
- Linux chain is `bwrap` then Landlock (`PLATFORM_CHAINS.linux`), probed once:
  - `bwrap`: must be installed (`apt-get install bubblewrap`) **and** the
    container must allow `unshare(CLONE_NEWUSER)` — the default Docker seccomp
    profile denies namespace syscalls, so bwrap fails with
    `Creating new namespace failed: Operation not permitted`.
  - Landlock: needs the kernel compiled with `CONFIG_SECURITY_LANDLOCK`. Unraid
    6.18 kernel does **not** (kallsyms shows address-0 weak symbols for
    `__x64_sys_landlock_*`; the syscall returns ENOSYS). Container-side
    `--security-opt seccomp=unconfined` does **not** fix this.
- Fix that worked: container started with `--security-opt seccomp=unconfined`
  (removes the namespace-syscall seccomp filter) + `bubblewrap` installed.
  After that DSH auto-selects bwrap and `workspace-write` works, with `/etc`
  read-only and only the workspace root + `/tmp` writable.

### 9.2 Sandbox makes most of the filesystem read-only

Under `workspace-write`, bwrap mounts `/` read-only and binds only the
workspace root (plus `--tmpfs /tmp`) writable. Consequences:

- `~/.gitconfig`, `~/.git-credentials`, `/root/.android`, `/opt/android-sdk`
  are all **read-only** inside the sandbox. Commands that need to write there
  fail with `Read-only file system` or `not writable`.
- Gradle: point `GRADLE_USER_HOME` into the workspace
  (`export GRADLE_USER_HOME=<workspace>/.gradle-home`) — `.gradle-home/` is
  gitignored. Without it the wrapper tries `/root/.gradle` (read-only) and
  dies on the lock file.
- Android debug signing: AGP wants `/root/.android/debug.keystore`. Set
  `ANDROID_USER_HOME=<workspace>/.android-home` (also gitignored) so the
  keystore lands on writable storage.
- SDK components (`platforms;android-36`, `build-tools;36.0.0`,
  `cmake;3.31.6`, `ndk;28.0.13004108`) must be installed with an escalated
  (danger-full-access) bash call, because `/opt/android-sdk` is outside the
  sandbox writable roots.

### 9.3 Long gradle builds die in background bash jobs

Running `./gradlew :app:assembleDebug` as a **background** job gets killed
mid-JNI-compile (daemon logs show the build stopping at a C/C++ warning with
no error). Run builds **foreground** with a large timeout instead. A fresh
container does a full 4-ABI JNI build (librime + plugins) — several minutes;
the second build is fast because the JNI cache persists in `app/build`.

### 9.4 Git credentials live on the Unraid host, not the container layer

- The container layer (`/home/dsh`, `/root`) is **not** persistent across
  container recreation — `.git-credentials` / `.gitconfig` / `.ssh` put there
  are lost on rebuild.
- The persistent, safe place is the Unraid `shfs` volume mounts, notably
  `/ssh-keys` (host-side key store). This repo pushes to Gitea
  (`git@192.168.1.50:Home/trime.git`) over SSH with the key
  `/ssh-keys/gitea-dsh-dev-docker`; the remote URL and `core.sshCommand`
  (with `-i /ssh-keys/gitea-dsh-dev-docker`) are set in the repo-local
  `.git/config` so plain `git push` works without extra setup.
- The container runs as **root** (matches the host's other machines), so
  `git config --global` writes fail (read-only home); use repo-local config
  or env-var injection (`GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory
  GIT_CONFIG_VALUE_0=...`) instead. After a container rebuild, `chown` the
  workspace to root if a prior root-run session left it owned by `dsh` (or
  vice versa) so git's dubious-ownership check passes.

### 9.5 spotless 扫描范围（学到的教训：别用全树 `**/*.kt`）

- 历史问题：根 `build.gradle.kts` 曾用 `target("**/*.kt", "**/*.kts")`，Gradle 为匹配必须
  **从仓库根全量遍历**（每个节点 stat 一次）。本工作树的 vendored
  `app/src/main/jni/boost`（约 5.9 万文件、661MB）加上默认 git-attributes 行尾处理
  （`GitAttributesLineEndings` 也要遍历文件集合），使 `:spotlessKotlin*` 在本容器里
  >7 分钟仍跑不完（CPU 空转在 `stat`/`FileTreeWalker`，见线程栈证据）。
- 修复（`4abf2df5`，根 `build.gradle.kts`）：
  - `target` 收窄到真实 Kotlin 源码根：`app/src/*/java/**/*.kt`、
    `codegen/src/*/java/**/*.kt`、`codegen/src/*/kotlin/**/*.kt`、`build-logic/**/*.kt`、
    `*.kts`、`app/*.kts`、`codegen/*.kts`、`build-logic/**/*.kts`；
  - 删除巨型 `targetExclude`（减法集合仍要遍历被排除树，`.gradle-home/**` 同样上万文件）；
  - `lineEndings = com.diffplug.spotless.LineEnding.UNIX`，绕开 git-attributes 整树扫描。
  - 效果：`:spotlessKotlinCheck` 从 >420s 卡死降到 ~5s。
- **覆盖规则**：repo 自管（`git ls-files '*.kt' '*.kts'`，共 ~314 个）全部落在上述 target 内；
  子模块（gitlink）天然不在 `ls-files` 范围，无需处理。**新增 Kotlin/KTS 若放标准位置之外**
  （如 `app/src/*/kotlin`、`scripts/foo.kt` 等），必须同步扩展 `build.gradle.kts` 的 target，
  否则不会被格式化检查覆盖。

### 9.6 assembleDebug 4-ABI 在本容器的限制（上游问题 + 单 ABI 绕过）

- 完整 `:app:assembleDebug` 会失败在 32 位 `armeabi-v7a`：上游
  `librime/plugins/librime-lua/thirdparty/lua5.4/liolib.c` 在 NDK r28 报
  `fseeko/ftello` undeclared（32 位 bionic 需 `_LARGEFILE_SOURCE`）。这是**上游子模块**问题，
  按 CLAUDE 规则不 patch 子模块；需要时走上游修复或换 NDK。
- 绕过（模拟器 x86_64 可用）：
  ```bash
  ANDROID_USER_HOME="$PWD/.android-home" \
  GRADLE_USER_HOME="$PWD/.gradle-home" \
  ./gradlew :app:assembleDebug -Pandroid.injected.build.abi=x86_64 --offline
  ```
  - `ANDROID_USER_HOME` 必须指向可写目录（默认 `/root/.android` 只读，签名会失败）；
  - 单 ABI 注入时 APK 落在 `app/build/intermediates/apk/debug/*-x86_64-debug.apk`
    （`outputs/apk/debug` 的 listing 是旧的），需要时自行 copy 到 outputs。

