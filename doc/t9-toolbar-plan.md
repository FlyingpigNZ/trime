# 计划：① 方案级工具栏覆盖  ② T9/小鹤双拼 拼音拆解列

> 状态：**已实现（进行中）**。两个功能已按本计划落地：
> - ① 方案级工具栏：`<schemaId>.extended.yaml` 的 `tool_bar` 覆盖
>   （`__replace` 缺省 merge），schema 切换时由
>   `ThemeManager.applySchemaToolBar` 应用/恢复。
> - ② T9 拼音拆解列：音节表 + 小鹤键位表数据、数字串→拼音序列解码器、
>   覆盖第一列的可滚动浮层（拦截触摸），点选拼音喂回 Rime。
> 剩余工作见文末「待确认 / 已定」的「仍待定」节与
> `doc/repo-knowledge.md` §4.1。

两个新功能，都已读过代码、摸清现有架构。下面是计划。**设计点已按用户的
多轮反馈修正**：
- ① 跟随现有 Kotlin 组件模型规则；`__replace` 命名已确认（`merge` 缺省）。
- ② 覆盖第一列 → 浮动窗口**纵向可滚动**且**必须拦截触摸**（被盖住的标点键
  不触发）；启用字段 `t9_disambiguation`。
- ① + ② **共享一个数据载体**：`<schemaId>.extended.yaml`（如
  `wanxiang_flypy_t9.extended.yaml`），按 schema 命名、对应输入法，
  **不碰 `.schema.yaml`**（Rime 编译文件），同时承载 `tool_bar` 覆盖、
  `t9_disambiguation` 与音节表/双拼键位表。

---

## 背景 / 现状小结（详见 `doc/repo-knowledge.md`）

- **工具栏**：`chrome.yaml` 的 `tool_bar` 段 → `ComponentResolver` 深合并进
  `Theme.tool_bar` → `ComponentThemeLoader`（`THEME_SECTIONS` 含 `tool_bar`）
  → `Theme.decode` → `Theme.toolBar`。**一个包内所有键盘共享同一个
  `theme.toolBar`**。运行时读取点：`AlwaysUi.kt`、`ButtonsBarUi.kt`、
  `TabUi.kt`、`SegmentsWindow.kt`、`FontManager.kt`。
- **T9 键盘第一列**：`keyboard.yaml` 的 `t9` 键定义里，每行第一列是标点键
  （`，`/`。`/`？`/`！`）。数字键 `2-9` 带字母 hint。
- **T9 输入流**：数字键在 `super_processor.lua` 对 `is_t9` 放行 → `speller`
  的 `/9jian` 字母折叠成数字（`xlit/.../22233344455566677778889999/`）→ Rime
  翻译。**目前没有「数字串→合法拼音列表」的用户交互列**，也没有用于「数字串
  → 全套拼音切分」的现成数据。
- **schema 可读性**：`RimeConfig.openSchema(schemaId)` 只能读 int/string/list；
  而 `.schema.yaml` 源文件与计划新增的 `<schemaId>.extended.yaml` 都随包拷到
  `PackageStore.workspaceDir(activeId)`，应用可用 `Yaml` 工具直接解析。
  （`.schema.yaml` 归 Rime 编译，改它不动；应用自定义段一律放 `.extended.yaml`。）

---

## Feature ① 方案级工具栏（每个方案可有自己的键盘工具栏）

### 需求
现在一个包里所有键盘共用一个 `tool_bar`。不同键盘形式需要单独的工具栏。
允许按当前 schema 覆盖 `tool_bar` 内容，可 `replace` 或 `merge` `chrome.yaml`
里的内容（由怎么写来决定）。

### 设计（跟随现有 Kotlin 组件模型规则）—— 用独立 `.extended.yaml`

#### 载体：随包携带的 `<schemaId>.extended.yaml`
与②统一思路，**不用 `.schema.yaml`**（那是 Rime 编译输入法用的文件，不能塞应用
自定义段）。每个方案对应一个**按 schema 命名的扩展文件**，随包携带、随
`rime_files`（或独立资源）拷入 workspace，应用用 `Yaml` 工具直接解析，Rime 不碰它：

- 文件名：`<schemaId>.extended.yaml`，例如
  - `wanxiang_flypy_t9.extended.yaml`
  - `wanxiang_t9.extended.yaml`
- 作用：**既承载该方案的工具栏覆盖，也承载 T9 disambiguation 配置**（见 Feature ②）。
  文件按 `schema_id` 命名，天然绑定到对应输入法；对同一包内的不同方案各有一份，
  互不影响，也不影响现有 `.schema.yaml` 定义。

建议结构：
```yaml
# wanxiang_flypy_t9.extended.yaml
schema_id: wanxiang_flypy_t9   # 绑定目标 schema（可选，用于校验/匹配）
tool_bar:
  __replace: false             # 缺省 false = merge；true = 整体替换 chrome.yaml 的 tool_bar
  buttons: [ ... ]             # 与 chrome.yaml 的 tool_bar 同形状
  button_spacing: 5
  ...
t9_disambiguation:            # 见 Feature ②；也放这个文件
  enabled: true
```

#### 合并 / 替换语义（沿用现有组件模型规则）
现有组件模型对 `style` 这类标量/映射节用 `mergeMappings` 深合并（嵌套映射递归
合并，叶子覆盖）；对命名节按 id 后写覆盖。`tool_bar` 作为**标量/映射节**处理：

- **merge（缺省，`__replace: false`）**：`mergeMappings(theme.toolBar, extToolBar)`，
  即"extended 里的内容深合并到 chrome.yaml 的 tool_bar 上"。
- **replace（`__replace: true`）**：整体用 ext 的 `tool_bar` 替换 `theme.toolBar`。
- `__replace` 是自定义意图字段，由调用方显式处理，避免被 Rime 误读。

### 改动点（Kotlin）
1. 新增"解析 `<schemaId>.extended.yaml`"的入口：在 schema 切换时，从
   `PackageStore.workspaceDir(activeId)` 找 `<schemaId>.extended.yaml`，用 `Yaml`
   工具解析成 `Node.Mapping`。
2. `data/theme/model/ToolBar.kt`：加 `merge(other: ToolBar)` 与 `replace` 支持；
   `decode` 读取 `__replace`（`extended` 的 `tool_bar` 段可复用 `ToolBar.decode`）。
3. `data/theme/Theme.kt`：新增"把 `<schemaId>.extended.yaml` 的 `tool_bar` 覆盖到
   base theme"的逻辑（`mergeSchemaLayout` / 新方法 `applySchemaToolBar`），产出
   有效 `toolBar`。
4. 运行时注入（按「当前 schema → 有效 toolbar」集中化）：
   - `ThemeManager` 增加按当前 schema 计算有效 `toolBar` 的入口。
   - 在 `InputView.handleRimeMessage(SchemaMessage)` 或
     `Rime.updateSchemaCached(schemaId 变化)` 时解析并**替换/合并** `Theme.toolBar`，
     然后触发工具栏重建。
   - 关键：把「哪些地方读 toolbar」集中成 `Theme.toolBar` 的一个**按 schema 计算的
     getter**（或抽 `ToolBarResolver`），否则 `AlwaysUi/ButtonsBarUi/TabUi/
     SegmentsWindow` 各处读同一个静态值会漏掉方案覆盖。
5. 校验：
   - `DefinitionValidator`：校验 `<schemaId>.extended.yaml` 的 `tool_bar` 段颜色
     引用、`__replace` 合法性、`schema_id` 匹配。
   - `validate-definitions.py` / `ComponentValidator`：新增对应的纯数据校验
     （extended 文件 `tool_bar` 形状 + `schema_id` 存在性 + 与包内 schema 一一对应）。
   - 文档：更新 `doc/definition-schema.md` 与 `doc/repo-knowledge.md`。

### 设计原则确认
- 数据优先：schema 里怎么声明 → 决定 merge/replace；代码不硬编码。
- `__replace` 是自定义意图，调用方显式处理；缺省 merge 走既有 deep-merge，
  与 `ComponentResolver.mergeMappings` 语义一致。

---

## Feature ② T9 / 小鹤双拼 拼音拆解列（覆盖键盘第一列，可纵向滚动）

### 需求
T9 重码率太高。应用里给一个过滤机制：用户在全拼时输入 `426`（代表 hao、
gao、…），应用列出 **`426` 所有合法的拼音组合**，放到 T9 符号列（即键盘
**第一列**），做成可上下滚动。用户选完对应拼音后发送给 rime engine，从而
大幅降重码。

小鹤双拼同理，区别是：小鹤**只输入一个声母 + 一个韵母**（hao、gao 从 `426`
变成 `42`），但拼音列应显示**完整的拼音序列**（`hao`、`gao`），而非键码
`hc`、`gc`。

### 设计要点

#### 数据：内置音节表（纯数据，随包）— 放 `<schemaId>.extended.yaml`，不放 `.schema.yaml`
- **`.schema.yaml` 是 Rime engine 用来编译输入法的文件**，不能往里塞应用层自定义段
  （Rime 可能不认）。这次数据放**与①共享的独立扩展文件**：
  - 文件名：`<schemaId>.extended.yaml`（如 `wanxiang_flypy_t9.extended.yaml`、
    `wanxiang_t9.extended.yaml`），放在包的 `rime/` 目录，随包拷入 workspace，
    应用用 `Yaml` 工具直接解析，Rime 不碰它。
- 内容：一份标准拼音音节表（约 400+ 音节，无声调全拼），每个音节带派生码：
  - `t9_code`：全拼 → T9 数字（`A-Z→222333444...`，确定性推导）；
  - `flypy_code`：小鹤双拼的（声母+韵母）键位码 → 规范化字母码；
  - `flypy_t9_code`：小鹤双拼码再折叠为 2 位 T9 数字。
- 小鹤键位映射（声母表 + 韵母表 → 键位）是**方案特定数据**，单独成表。后续
  要支持自然码等方案时，把「方案 → 双拼键位表」做成可替换数据。
- 存哪：
  - 随包携带 `rime/<schemaId>.extended.yaml`（随 `rime_files` 拷入 workspace）。
  - **倾向随包 YAML**，便于按方案扩展；若担心启动解析开销，可打包成预编译资源。
- 这就让「数据来源」与 Rime 编译解耦：扩展文件是普通资源，应用读它，Rime 不碰它。

#### 运行时解码：数字串 → 合法拼音序列集合
- 监听输入（`ime/bar/InputBarDelegate` 候选更新，或 composition 流），拿到
  当前**原始输入**数字串（可经 `rime.getRawInput()` 或 `CompositionProto.preedit`）。
- 反解算法：
  - **全拼 T9**：用 DP/前缀切分，把数字串切成一串合法全拼音节，令各音节
    `t9_code` 拼接 == 输入串 → 得到候选拼音序列集合（如 `426` → `hao`、
    `gao`、`hai`? 视音节表而定）。
  - **小鹤双拼 T9**：数字串按 **2 位/音节** 分组，每组用双拼键位表还原成
    （声母+韵母）→ **完整全拼**（`hao`、`gao`），**绝不显示键码** `hc`/`gc`。
  - 空串 / 非 T9 方案 / 无法反解 → 列表为空，不打扰。

#### UI：可纵向滚动的拆解列（覆盖第一列）—— 浮动窗口必须拦截触摸
用户确认的交互模型：
- 例：T9 键盘显示，用户输入 `hao`（数字串）。**按 T9 键盘第一列的尺寸**，
  动态生成一个**可上下滚动的浮动窗口**，覆盖在键盘第一列上方。
- **关键**：用户点击命中这个浮动窗口时，触发的是**浮动窗口里的拼音条目**，
  **被遮盖的第一列标点键必须收不到点击**（不能触发标点上屏）。也就是说
  浮动窗口要**自己吃掉触摸**，不能让事件落到下面的键盘键上。
- 因为可能上下滚动、且要与键盘视图解耦，**用独立浮层/面板**，与 `KeyboardView`
  分离（复用「浮层附着」模式，参考 `LiquidWindow` 的 `BoardWindow`/
  `ResidentWindow` 机制，或 `CandidatesView` 这种独立 View）。

实现要点（保证"拦截"）：
- 浮动窗口是一个**独立 View/窗口**，尺寸与键盘第一列对齐（宽=第一列宽度，
  高=第一列总高），放在 `KeyboardView` 之上。
- 窗口自身 `onTouchEvent` 处理点击（命中拼音条目）与滚动；**`onTouchEvent`
  返回 `true`**（或对命中区域拦截），从而阻断事件传给下层键盘键。可参考
  `PreeditTextView.onTouchEvent`、`GestureFrame` / `DragSelectTouchListener`
  这类「自身处理触摸」的现有实现。
- 窗口内部用 `RecyclerView` **纵向可滚动**列出所有合法拼音序列。
- 显示：每项一个完整拼音序列（单音节 `hao`，多音节一个完整切分）。
- 点击某序列：`clearComposition()` + `rime.simulateKeySequence(拼音)` 把该
  拼音喂给 Rime（对齐 `LiquidWindow.triggerSymbolInput` 的上屏手法）→ Rime
  按拼音重翻，降重码。
- 交互：选中后隐藏浮层；输入变化时实时重建列表。

> 关键澄清（回应"下面的符号键盘会不会被触发"）：**不会**。浮动窗口覆盖并
> 拦截触摸，落在浮动窗口上的点击进浮动窗口的逻辑，不会落到被遮住的标点键。

#### 启用位 — 放 `<schemaId>.extended.yaml`，不放 `.schema.yaml`
- **`.schema.yaml` 是 Rime engine 用来编译输入法的文件**，不能塞应用自定义段。
  因此启用位 + 音节表数据都放**与①共享的扩展文件** `<schemaId>.extended.yaml`，
  随包携带、随 `rime_files` 拷入 workspace，应用用 `Yaml` 工具直接解析。
- 字段名 `t9_disambiguation`（你的指定），默认对 `t9=true` 的方案开启：
  ```yaml
  t9_disambiguation:
    enabled: true
  ```
- Kotlin 侧判断 T9 方案：`super_comment_preedit.lua` 的 `SCHEME_CAPABILITIES`
  （`wanxiang_t9`/`wanxiang_flypy_t9` 已标 `t9=true`）是 Lua 侧锚点；Kotlin 侧
  需有对应的「当前 schema 是否 T9」判断，可作为判断依据。

### 改动点
- 新增：
  - 拼音音节表 + 双拼键位表数据（`data/` 或资源）。
  - `ime/` 下 T9 拆解列面板（View + RecyclerView Adapter + 解码器）。
  - 解码器（数字串 → 拼音序列集合），全拼/双拼两套路径。
- 修改：
  - `ime/keyboard/KeyboardWindow.kt` / `KeyboardView.kt`：T9 键盘激活时挂浮层，
    其它键盘隐藏。
  - `ime/bar/InputBarDelegate.kt`：输入变化驱动解码/刷新。
  - `core/RimeSchema.kt`：读 `t9_disambiguation` 启用位。
  - `super_comment_preedit.lua`（如需同步显示）。`wanxiang.lua`
    `get_input_method_type` 已能区分 `t9`/`flypy`。
- 校验：
  - `script/` 加音节表可反解校验（键码串 ↔ 拼音一一对应，防 `426→hao/gao` 漏项）。
  - Lua 改动跑 `luac -p`/`loadfile`；Kotlin 跑编译与单测。

---

## 跨功能注意事项（遵守仓库规则）
1. **schema-first**：新规则先落 schema/文档（`doc/definition-schema.md`）和
   校验器，再让 Kotlin 消费；不在代码里写魔数或值兜底。
2. **不动 librime 子模块**：仅用公开 API（`simulateKeySequence`/
   `clearComposition`/`getRawInput`）；改动都在 `java/` 与
   `rime/lua/wanxiang/*.lua`。
3. **数据校验**：音节表用 `script/` 做可反解校验；Lua 语法检查；Kotlin 编译。
4. **行为兼容**：默认路径不变（非 T9 方案不显示拆解列；无 schema `tool_bar`
   时工具栏沿用 chrome.yaml）。
5. **命名与语义**：`__replace` 是自定义意图字段，明确由调用方处理，避免被
   Rime 误读；字段名最终以文档定死为准。

---

## 拟改文件清单（摘要）
- ① + ②（共享载体）：新增各方案的 `<schemaId>.extended.yaml`
  （如 `wanxiang_flypy_t9.extended.yaml`、`wanxiang_t9.extended.yaml`，
  放 `rime/`，含 `tool_bar` + `t9_disambiguation` + 音节表/双拼键位表）。
  Kotlin：`data/theme/model/ToolBar.kt`（merge/replace）、
  `data/theme/Theme.kt`（applySchemaToolBar）、`data/theme/ThemeManager.kt`、
  新增 `<schemaId>.extended.yaml` 解析入口、
  `data/theme/DefinitionValidator.kt`、`data/theme/component/{ComponentResolver,
  ComponentValidator}.kt`、`doc/definition-schema.md`。
- ②（功能本身）：新增 T9 拆解列面板 + adapter + 解码器（数字串→拼音序列，
  全拼/双拼两套）、`ime/keyboard/{KeyboardWindow,KeyboardView}.kt`（挂浮层并
  拦截触摸）、`ime/bar/InputBarDelegate.kt`（输入驱动）、`core/RimeSchema.kt`
  （判断 T9/读启用位）、`super_comment_preedit.lua`（如需同步显示）、
  `validate-definitions.py` + 测试。

---

## 待确认 / 已定（下次动手前）
已定：
- **① `__replace` 命名**：你 ✓ 接受。缺省 merge（走现有 `mergeMappings` 深合并）。
- **① + ② 数据载体**：统一用 **`<schemaId>.extended.yaml`**（如
  `wanxiang_flypy_t9.extended.yaml`、`wanxiang_t9.extended.yaml`），
  由 schema 命名对应输入法，**不碰 `.schema.yaml`**（Rime 编译文件），
  同时承载 `tool_bar` 覆盖、`t9_disambiguation` 启用位与音节表/双拼键位表。
- **② 浮动窗口拦截**：✓ 明确 —— 浮动窗口覆盖第一列并**自己吃掉触摸**，被盖住的
  标点键不触发；浮动窗口内部纵向可滚动。
- **② 启用位字段**：`t9_disambiguation`，默认对 `t9=true` 方案开启。
- **② 音节表数据量**：全拼 400+ 即够；小鹤双拼键位表单独成表。

仍待定 → 已实现中确定：
- `<schemaId>.extended.yaml` 的**存放与打包路径**：随包的 schema 文件旁（zip 根，
  与 `wanxiang_t9.schema.yaml` 同级），安装后落到 workspace 根目录，
  `SchemaExtensionResolver` 在那里查找。
- 浮动窗口的**挂载方式**：独立 View 叠在 `KeyboardView` 所在 FrameLayout 之上
  （`KeyboardWindow.attachKeyboard` 时挂载），内部 `RecyclerView` 消费触摸，
  被盖住的标点键不触发。
- `<schemaId>.extended.yaml` 的**工具校验**：`script/extended_validator.py` +
  Kotlin `DefinitionValidator.validateExtendedFiles`，校验 `schema_id`、
  `tool_bar`/`__replace` 形状、音节表键码一致性。
