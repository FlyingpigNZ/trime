# Handoff — 键盘背景动态更换（in-app keyboard background editor）

> 每个新 session 开工前必须通读本文件 + `doc/repo-knowledge.md`（见 `CLAUDE.md`
> 顶部的必读条款），再决定下一步。最后更新于：提交 `dc303fbf` 之后的方案 A 迭代
> （本地已提交，尚未 push，见 §5）。

## 1. 状态摘要

- 功能：在「键盘样式」设置内新增「键盘背景」编辑器，动态更换当前配色方案的键盘
  背景图，按日/夜（light/dark）独立定制；持久化走 app 自有的
  `workspace/customization.yaml` 定制层，**不改包源 color.yaml**。
- Git：`main` 分支，已本地提交 `dc303fbf`
  （feat(theme): in-app keyboard background editor with customization.yaml overlay）
  与最新的方案 A 提交（本地 main 顶端）。
  **均尚未 push**（目标 remote：`origin_home`；推送前需 `make style-lint` 通过，
  已跑过 spotless + 单测 + compile，可审阅后 push）。
- 验证状态（本机可复现）：
  - `GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew :app:compileDebugKotlin --offline` → SUCCESS
  - `ThemeCustomizationTest` + `ThemeCustomizationWriterTest` + `data.theme.*` 全包单测 → PASS
  - `spotlessApply` + `spotlessCheck` → PASS
  - 真机/模拟器手动冒烟：更换图片可用；日/夜切换与整页配色联动可用；缺省回退可用；
    UI 细节按下面「已确认 UX」迭代过。

## 2. 已确认的 UX（用户拍板，勿随意回退）

1. 「键盘样式」设置页新增行「键盘背景」，点击打开 `KeyboardBackgroundEditorActivity`
   （Material 主题 `Theme.Trime.KeyboardBackgroundEditor`，manifest 已注册）。
2. 主界面：预览（真实配色 + 真实键布局渲染，见 §5 方案 A）与日/夜 radio **同组**；
   radio **横排在预览下方**（预览占满整卡宽度）；整组屏幕纵向居中，包在圆角
   Material 卡片（`MaterialCardView`）内；**整页/卡片配色跟随所选日/夜档**
   （`applyModeColors()`，浅=灰底白卡，深=深底深卡）。
3. 动作按钮放预览组正下方（不贴屏幕底）：**选取 / 应用 / 缺省**，按钮**固定等宽
   `ACTION_BUTTON_WIDTH_DP = 96`**、居中对齐、带间距；显隐规则：
   - 选取恒显；应用仅在有裁剪待确认图时显；缺省仅在当前 配色×档 已有自定义时显。
4. 无取消按钮：预览页返回=退出；裁剪页返回=退回预览（`OnBackPressedCallback`）。
5. 裁剪页：全屏图片 + 固定键盘宽高比范围框；单指拖动只平移（尺寸不变）、双指等比
   缩放；底部「裁剪 | 复位」按钮，尺寸/样式与主按钮一致。
6. 「缺省背景」作用于**当前选中的日/夜档**（只删该 scheme×mode 的 key 与槽位文件）。
7. radio 初始档 = 系统当前生效档（开「跟随系统日夜」且夜间→夜间，否则日间）。此为
   用户确认的设计，不要再改成“不跟随也默认暗色”。

## 3. 数据层要点（已实现 + 单测覆盖）

- `app/src/main/java/com/osfans/trime/data/theme/ThemeCustomization.kt`
  - `customization.yaml` schema（首版）：
    ```yaml
    color_schemes:
      <schemeId>:            # 必须是解析后主题里真实存在的配色；id 可含 '/'
        light:               # 部分 palette 覆盖
          keyboard_background: custom_<scheme>_day.png
        dark:
          keyboard_background: custom_<scheme>_night.png
    ```
  - **语义：按 scheme×档覆盖**，落在 `preset_color_schemes.<id>.light/dark`
    解析副本上，**绝不改 `colors.<palette>` 源**（共享 palette 的多配色互不串扰）。
  - legacy 扁平 scheme 先归一化为 light/dark 全量副本再合并；无 dark 的 scheme
    自动以 light 副本兜底（因此“源无 dark 也可单独定制夜间”）。
  - 写盘原子化（tmp+rename）；`slotFileName()` = `custom_<sanitizedScheme>_<day|night>.png`，
    覆盖式清理、`custom_` 前缀防误删包图；`updatedColorSchemeBackground(null)=移除 key，
    空分支自动剪枝。
- `DefinitionValidator.validateCustomization`：未知 scheme/mode/key 响亮报错；
  drawable 键（`DRAWABLE_KEYS`）放行文件名，其余键必须 hex。
- `ComponentThemeLoader.loadTheme`：resolve 后读 `customization.yaml` → 校验 →
  深合并 → 才 `Theme.decode`；`PackageThemeLoader` 组件包全链路自动生效。
- 文件解析用 `com.osfans.trime.util.yaml.*`（只有 parser 无 serializer）；写盘用
  SnakeYAML（`import org.yaml.snakeyaml.Yaml as SnakeYaml`），app 自有机器文件可丢注释。
- 图片解析运行时不变：`ColorManager.resolveImageFilePath` 先
  `<workspace>/backgrounds/<background_folder>/<name>` 再回退
  `<workspace>/backgrounds/<name>`；槽位文件写 `backgrounds/` 根层即可。

## 4. UI/Activity 关键实现

- `KeyboardBackgroundEditorActivity`（.ui.main.settings.theme）
  - scheme 解析回退：prefs 里 id → `"default"` → 第一个配色（`currentSchemeId`），
    与 ColorManager 行为一致；找不到配色时弹「未找到配色方案」，不再误报“无法读取图片”。
  - 选图：`GetContent("image/*")` → 采样解码（2048 上限）+ EXIF 旋转 → 裁剪
    （`KeyboardBackgroundCropView`）→ 回预览待确认 →「应用」写 PNG + 更新
    customization + best-effort `PackageThemeLoader.load` + `applySchemaLayout(false)`
    刷新运行中主题。
  - `KeyboardBackgroundPreview`：**当前是简化示意**（见 §5 的下一步）。
  - 配色常量（日/夜窗口、卡片、文字）在 companion object；按钮固定宽 96dp。
- Material 化：`app/build.gradle.kts` 直接加
  `implementation("com.google.android.material:material:1.4.0")`（本地缓存，离线可编）；
  `themes.xml` 新增 `Theme.Trime.KeyboardBackgroundEditor`
  （parent `Theme.MaterialComponents.Light.NoActionBar`，主色沿用现有 colorPrimary/colorAccent）；
  manifest 给 Activity 挂该主题；按钮/radio 用 MaterialButton/MaterialRadioButton。
- strings：en / zh-rCN / zh-rTW 已加 `keyboard_background_*` 系列
  （选取/Pick、应用/Apply、缺省/Default、裁剪/Crop、复位/Reset 等）。

## 5. 下一步（用户已认可方向）

### 方案 A（真实配色 + 真实键布局的预览渲染）—— 已实现（本地 main 已提交，尚未 push）

编辑器预览已从"简化示意"升级为按当前 scheme×档的真实数据渲染：

- 新预览 View `KeyboardBackgroundPreview`（ui.main.settings.theme）接收
  `RenderModel`（所选档 palette + theme `fallback_colors` + 真实 `preset_keyboards`
  条目 + GeneralStyle + 候选/键盘 dp 尺寸）；无可用主题时保留原简化网格回退。
- 取色完全绕开 ColorManager 单例（设置页进程可能没跑 IME）：本地镜像其
  `BuiltinFallbackColors + theme.fallbackColors` 解析链 + `ColorUtils.parseColor`。
- 键布局镜像真实 `Keyboard` 的权重换行算法：新增纯 Kotlin
  `PreviewKeyboardLayout`（无 Android 依赖、可 JVM 单测）；占位键（无 click token
  的 yaml 键）只推进不绘制、让背景图从空隙露出；行高按键盘带等比缩放、末行吸收
  取整余数；沿用真实 配置→style 的 gap/圆角/边框/offset first-non-zero 链。
- 绘制内容：键面背景/文字/符号/边框用 palette 真实色；文字/符号/外观分类
  （sticky/functional→off 键色系）直接复用 IME 绘制前的纯数据层
  `KeyActionDefinition.parse(token, theme.presetKeys)`（Key/KeyView 同一入口，
  含 preset label 如 `14keyqw→"Q W"`、`num1→"1"`、字母/内联映射）；`ic@` 图标
  字与空格 schema 名这类需要 live 状态的不画（留空）；候选条用真实
  `candidate_*`/`hilited_*` 配色画半透明表面 + 示例候选 chips（文案=string-array
  资源，三语）。
- 接线：Activity 在每次 `refreshPreview()`（含日/夜切换、应用、缺省后）重建
  RenderModel；预览键盘按 IME 的数据链选择（活动包 manifest `default_keyboard`
  绑定 → 与所编辑配色同名的预设 → `default` → 首个），键区再按 style
  `keyboard_padding` 左右留边——真机反馈第 1 轮后如此镜像真机几何。设置页没有
  Rime 会话状态，精确到“当前正在打的键盘/ascii 变体”仍不做（已注释说明）。
- 验证（本机可复现）：`:app:compileDebugKotlin` SUCCESS；新增
  `PreviewKeyboardLayoutTest`（权重换行/占位/列上限/尾行吸收/行长）+
  `DefaultKeyboardLayoutCompatibilityTest`（真实 Default 包 keyboard.yaml 解码 →
  期望 4 行、10/9 键字母行、5-weight 侧位占位）→ PASS；spotlessApply/Check PASS。
- 简化与遗留（有意为之，勿当 bug 回修）：
  1. 键均按 idle 态绘制：依赖 off/on 状态分类、无 per-key 色的键会近似为普通键色；
  2. 不画 `ic@` 图标字（用 iconics 需 IME 字体上下文），空格键 schema 名、
     需 Rime 状态的动态 label 不画，此类键面留空；
  3. 候选条文字为示例文案、非实时候选；
  4. 预览键盘=包 manifest 绑定/同名/默认 链上的代表键盘，非"当前正在打的键盘"；
  5. 图片/文字几何按 style 级 dims（candidate_view_height/keyboard_height），
     未区分每键 keyboard_height 配置——与既有裁剪宽高比口径一致；
  6. 未镜像 `expandKeypressArea=true` 的“邻键吸边扩展”：占位键只推进不绘制、
     背景从空隙露出（真 IME 在 expand 时会把占位宽度分配给相邻键）。
  （另：主线程不再读 customization/workspace/registry——按钮可见性基于 IO 缓存
  的 fileName；切日/夜档会保留待确认的裁剪（pending），仅换配色/布局重渲染。）
- **真机冒烟：已完成 1–3 轮**。前两轮反馈均已修：①键盘选择优先
  manifest `default_keyboard`/`session_last_keyboard`（切边、当前键盘、命名
  token 主文字、候选行坐标越界已修）。
  第 3 轮新问题（均已处理，待复冒烟确认）：
  1. 切换 schema（14键→9键）预览不跟随 → `KeyboardSwitcher.selectKeyboard`
     持久化当前键盘到新 `AppPrefs.session.lastKeyboard`；编辑器在打开/onResume
     时**快照**一次键盘 id（`previewKeyboardId`），刷新/应用/缺省沿用快照——
     既跟随外部 schema 切换，又不被编辑器自己的 theme 重建引发的键盘重解析
     （曾导致 14键 预览偶发跳 26键）影响。
  2. 二次应用裁剪对键盘不生效（重启才见）→ 改为**双槽位文件交替**
     （`custom_<scheme>_<day|night>.png` ↔ `…_2.png`，数据层
     `ThemeCustomization.nextSlotFileName`）：每次应用都换文件名写入并删除
     上一代，存储名变化 = 真实数据变化 → 走正常 theme 变更路径重建 InputView，
     **不再需要强制重绘**（已撤 `ThemeManager.forceRefresh`）。
  3. 设置入口改到「键盘样式」第二项（配色之后），不再是末行。
  4. 成功不再 toast（预览即反馈），仅失败 toast。
  复冒烟又发现并修复：缺省/应用成功后预览会在主题重载完成前读取旧 activeTheme
  （仍指向已删除的自定义文件名）→ 就地预览空白，重进才恢复。已把
  `refreshRunningTheme` 改为挂起并按“先重载主题 → 再刷新预览”串行执行。
  注：本会话容器无可用模拟器加速且视觉复核通道不可用，"视觉人工复核"仍未在
  会话内完成，勿宣称已复核。

### 实现原则（用户拍板）与可选后续（未拍板）

原则：键盘背景编辑器是**纯 UI 层功能，与 Rime 运行态尽量脱钩**；凡是能从数据
（theme/preset_keys/preset_keyboards/manifest/customization）读到的，不依赖
Rime 会话；重活尽量不压主线程（预览已按 token 缓存 `KeyActionDefinition` 解析，
避免 UI 线程每帧解析；图片解码/工作区主题读取仍同步于主线程——列入下方可选）。

可选后续（未拍板）：缺省时默认暗色档选项；Material 进一步细化；
EXIF 用 androidx 实现替代已废弃构造。
（**已落地**：选图解码、裁剪 PNG 编码与 customization/workspace 主题读取、manifest
registry 访问均已移至 IO（lifecycleScope+Dispatchers.IO，刷新单飞取消）；确认裁剪
后不再异步刷新以免覆盖 pending 预览。）

## 6. 环境/命令备忘

- 本机 sandbox：`~/.gradle` 只读 → **必须** `GRADLE_USER_HOME="$PWD/.gradle-home"`
  （仓库内已有该目录及 wrapper dist）。联网不稳定用 `--offline`。
- SDK：`local.properties` → `/home/jin/Android/Sdk`；JDK17。
- 常见命令：
  ```bash
  GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew :app:compileDebugKotlin --offline
  GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew :app:testDebugUnitTest --tests "com.osfans.trime.data.theme.*" --offline
  GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew spotlessApply spotlessCheck --offline
  ```
- 不 install、不 push 未验证内容；push 目标 `origin_home`（CLAUDE.md 规则）。

## 7. 未纳入提交的无关工作区内容

- `app/src/androidTest/res/`、`sample_theme_schemas/backgrounds/`：仓库既有未跟踪
  内容，与本功能无关，**不要顺手 add/commit**（提交时用显式文件清单）。
