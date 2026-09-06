# 键盘背景动态更换：方案与需求定稿

> 状态：方案定稿（本期只出文档，不写代码）
> 范围：键盘样式设置页新增「更改背景图片」，支持按日/夜（light/dark）独立定制键盘背景；
> 持久化走 app 自有的 `customization.yaml` 定制层，不改安装包源文件。
> 涉及 schema/校验的改动以清单形式给出，供实现阶段同步落地。

---

## 1. 背景与目标

Trime 键盘包（IME package）已支持在 `color.yaml` 配色 palette 里通过
`keyboard_background` 指定键盘背景图片。用户希望**在 App 内**动态更换该图片，流程为：

1. 「键盘样式」设置项中新增一个入口「更改背景图片」；
2. 点击后展示当前键盘渲染效果（简化模拟渲染，不要求像素级所见即所得），并提供
   「更换背景图片 / 缺省背景 / 取消」；
3. 更换图片：系统图片选择 → 裁剪页（按键盘渲染区宽高比，**可拖动 + 双指等比缩放**
   范围框）→ 回到预览并以新背景重新渲染预览；
4. 预览确认（此时才出现「确认」键）后：
   - 裁剪结果落盘为 workspace 内新图片；
   - 更新内存中当前配色对应日/夜档的 `keyboard_background`；
   - 持久化到 `customization.yaml`（**不改 color.yaml**）；
5. 「缺省背景」= 删除 `customization.yaml` 中对应 key 并删除自定图片，回退到包的默认值。

已确认的设计决策：

| 决策点 | 结论 |
|---|---|
| 作用范围 | 编辑器内提供「日间 / 夜间」切换（默认跟随当前系统生效档），把新背景写到**当前配色方案对应档**（light 或 dark），两档可各自不同 |
| 渲染预览 | 简化模拟渲染（画键帽网格/候选栏/背景），接受与真机渲染的细节差异 |
| 裁剪交互 | 固定为键盘渲染区宽高比；支持拖动与双指等比缩放（源图比例常与屏幕不同，必须可缩放） |
| 定制持久化 | workspace 根 `customization.yaml`，包更新 overlay 不覆盖（见 §3） |
| 源图质量 | 只做底线处理：采样解码防 OOM、EXIF 方向校正、解码失败提示；是否满意由用户自行判断 |

---

## 2. 现状与代码事实

### 2.1 主题/配色加载链（与本功能相关）

- 主题从 workspace 载入：`PackageThemeLoader.load(workspace)`
  → `ComponentThemeLoader.loadTheme(manifest)`。
- `ComponentResolver` 把 `color.yaml`（含 `colors:` + `color_schemes:`）等组件文件
  **解析合并**成 `sections`；来自 `colors`+`color_schemes` 的文件会被展开成
  `preset_color_schemes` 下每个 scheme entry 内 `light`/`dark` 的**完整内联 palette**
  （legacy 扁平 `preset_color_schemes` 保持扁平，见 §3.4 归一化），再交给
  `Theme.decode` 解码。
- 运行时状态：`ThemeManager.activeTheme`、`ColorManager.activeColorScheme`
  （`colors`/`darkColors`）、`resolvedPalette`；`ColorManager` 以**绝对路径**为 key 的
  `LruCache` 缓存背景 bitmap。
- 背景绘制：`InputView` 的 `keyboardBackground`（`ImageView` +
  `ScaleType.CENTER_CROP`，置于布局最底层）drawable 来自
  `ColorManager.getDrawable(ThemeColor.KEYBOARD_BACKGROUND)`；其可视区域即键盘渲染区
  （具体边界以该 View 的约束布局为准，裁剪比例实现时取此区域）。
- 图片定位：`ColorManager.resolveImageFilePath(value)` 先查
  `<workspace>/backgrounds/<background_folder>/<value>`，不存在再查
  `<workspace>/backgrounds/<value>`；`background_folder` 缺省为 `"backgrounds"`。
  → **value 存裸文件名**即可，物理文件放 `backgrounds/` 层即可被解析。
- 包更新是 **overlay 解压**（`PackageArchive.extractZipOverlay`）：只覆盖 zip 内存在的
  文件、**从不删除 workspace**、zip 外文件（用户数据等）自动保留。

### 2.2 数据取证：`sample_theme_schemas/简纯+14键.zip`

按包内真实 `color.yaml`（用户提供片段）确认关键事实：

1. **`colors:` 与 `color_schemes:` 分离**：palette 定义在 `colors`，
   scheme 在 `color_schemes` 里通过 `light:` / `dark:` **按名字引用** palette。
2. **scheme id 可以包含 `/`**，且语义正是「白天 / 黑夜」成对：

   ```yaml
   colors:
     default:        # 简白（白天 palette）
       name: 简白
       keyboard_background: default.jpg
       ...
     dark_temple:    # 暗堂（黑夜 palette）
       name: 暗堂／Dark Temple
       keyboard_background: dark_temple.jpg
       ...
     google_white:   # 简蓝（白天 palette，无 keyboard_background）
       ...
     google_black:   # 简黑（黑夜 palette，无 keyboard_background）
       ...
   color_schemes:
     default/dark_temple:
       name: 简白 / 暗堂／Dark Temple
       light: default
       dark: dark_temple
     google_white/google_black:
       name: 简蓝 / 简黑
       light: google_white
       dark: google_black
   ```

3. `keyboard_background` 是 **palette 级**字段，白天/黑夜 palette 各自携带自己的图
   （如 `default.jpg` vs `dark_temple.jpg`）；没有该 key 时走回退链
   `keyboard_background → keyboard_back_color`（`BuiltinFallbackColors`）。

**对设计的含义**

- “日/夜切换后 apply 到对应档”应落在**当前 scheme 的 light/dark 档**上；
- 定制必须**按 scheme 隔离**：同一 palette 可能被多个 scheme 引用（palette 级写回会串扰），
  因此定制层挂在「scheme × 档」上，而不是直接改 `colors.<palette>`；
- 现有 schema 文档用 `\w+` 描述 scheme 名（如 `doc/trime-schema.json` 的
  `preset_color_schemes.patternProperties`），**不匹配含 `/` 的 id，需同步修正**（见 §5）。

---

## 3. 数据层：`customization.yaml` 定制层

### 3.1 为什么新建 customization.yaml，而不是改 color.yaml

- `color.yaml` 属于安装包源文件；包升级 overlay 会用新版覆盖它，就地修改会丢。
- workspace 根目录的 `customization.yaml` 不在 vendor zip 里 →
  overlay 更新**天然保留**（前提：vendor 包永远不内建同名文件，且我们把它声明为
  app 自有文件；导出包时随 workspace 一起导出是合理行为）。
- 与“数据问题在数据层解决”的仓库规矩一致：base 数据保持纯净，用户自定义是独立覆盖层。

### 3.2 文件位置与生命周期

- 位置：`<workspace>/customization.yaml`（与 `manifest.yaml` 同级）。
- 归属：**app 自有、机器写**的文件；包 zip / `package_schema.py` 不得包含它。
- 导入：包 overlay 更新不删除它；新装包后不存在 → 视为空覆盖层。
- 主题每次载入都要重新解析并合并它（不能只在“编辑后”合并一次，否则重启/切包丢失）。

### 3.3 Schema（首版）

首版只覆盖 keyboard_background（便于后续扩展 style 等其它定制根）。

```yaml
# workspace/customization.yaml
# 只允许覆盖 theme 中已存在的 color_scheme；
# light/dark 为“该 scheme 对应档 palette 的增量覆盖”（部分 key，非全量 palette）。
color_schemes:
  default/dark_temple:        # 必须已存在于解析后的主题；id 允许含 '/'
    light:                    # 可选；缺省=不改白天档
      keyboard_background: custom_default_dark_temple_day.png
    dark:                     # 可选；缺省=不改黑夜档
      keyboard_background: custom_default_dark_temple_night.png
```

约束：

- 顶层键 `color_schemes`；值为 scheme id → `{light?, dark?}`；
- `light`/`dark` 的 value 是**部分 palette 映射**（只列要覆盖的 key）；
- key 集合：必须是该 scheme 对应档 palette 里已存在的 key，或 `ThemeColor` /
  `BuiltinFallbackColors` 已知 key（与 palette 校验同一套规则）；
- 值校验复用 palette 规则：`DRAWABLE_KEYS`
  （`root_background`/`candidate_background`/`keyboard_background`/
  `liquid_keyboard_background`）跳过颜色字面量检查（可填文件名或颜色引用/色值），
  其它 key 必须是 hex 色值；
- 未知 scheme id / 未知档 / 未知 key → **响亮报错**（不做静默忽略）。

> 后续扩展空间：顶层可增加 `style:`、`fallback_colors:` 等根，与本文件合并器同构。

### 3.4 合并时序（实现要点）

合并点必须在 **组件 resolve 之后、`Theme.decode` 之前**（当前代码在
`ComponentThemeLoader.loadTheme` 的 `sections` → `buildThemeNode` 之间）。

1. `ComponentResolver.resolve()` 得到 `sections`；
2. **归一化** scheme entries：凡没有显式 `light`/`dark` 子映射的（legacy 扁平
   `preset_color_schemes`），先展开成 `{light: <拷贝>, dark: <拷贝>}` 两份全量 palette，
   保证后续“部分覆盖”能安全落在副本上；
3. 读取并校验 `customization.yaml`（见 §5 校验清单）；
4. 对每个 `color_schemes.<id>.light/dark` 覆盖段，**深合并**到步骤 2 归一化后对应
   scheme 的 `light`/`dark` 全量 palette 上（mapping 递归合并、标量覆盖）；
5. 照常 `Theme.decode`。

**语义效果**（与真实数据自洽）：

- 源 scheme `default/dark_temple` 白天=palette `default`、黑夜=`dark_temple`：
  覆盖写在各自档副本上，`colors.default` / `colors.dark_temple` 不动，其他引用
  同 palette 的 scheme 不受影响；
- 源 scheme 没有独立 `dark`（如 Default 包所有 scheme 只有 `light`）：
  步骤 2 归一化会给 dark 造一份 light 的副本，因此**即便源无 dark，夜间档也能单独定制**
  —— 且该差异只存在于 customization 层，源文件保持“无 dark”原状；
- “缺省背景” = 删除对应 `(scheme, mode)` 的 `keyboard_background` 覆盖
  （段空则连段删）→ 回退链自然回到 base 的值/配色。

### 3.5 渲染解析不变

`customization.yaml` 只是给配色多提供了 `keyboard_background` 值；`ColorManager` 的
图片目录解析、缓存、fallback 全部复用现有逻辑，**无需改动渲染层**。

### 3.6 图片落盘与清理

- 槽位文件名：`custom_<schemeId 清洗后>_<day|night>.png`
  （scheme id 中非 `[A-Za-z0-9._-]` 的字符替换为 `_`，如
  `default/dark_temple` → `default_dark_temple`）。
- 物理位置：`<workspace>/backgrounds/`（与示例包自带图、运行时回退解析层一致），
  `custom_` 前缀保证不与包自带图冲突。
- 清理策略（回应“换一次清一次上次客制化背景”）：
  - 更换 = **覆盖同名槽位文件**，天然不留历史文件；
  - 「缺省背景」= 删除槽位文件 + 删除 customization key；
  - 只删除 `custom_*` 槽位文件，绝不碰包自带背景。
- 写盘顺序与回滚（防半套状态）：
  1. 解码采样 + EXIF 校正 → 裁切 bitmap → 写槽位文件（PNG）；
  2. 原子更新 `customization.yaml`（tmp + rename，仓库已有该模式先例）；
  3. 重载主题并刷新内存（见 §4.6）；
  4. 若第 2 步失败：删除刚写的槽位文件、状态不变、toast 报错可重试。

---

## 4. App UI 流程

### 4.1 入口

- 「键盘样式」设置页（`ThemeSettingsFragment`）在 `normal_mode_color` 附近新增一行
  「更改背景图片」（summary 可显示“当前：默认背景/图片名/无”）。
- 点击打开**背景编辑器**（建议独立 Activity 或 DialogFragment；裁剪手势与系统选择器
  用 Activity Result API，避免与输入法窗口抢焦点）。

### 4.2 背景编辑器状态机

| 状态 | 界面内容 | 允许动作 |
|---|---|---|
| S0 预览 | 日/夜切换（SegmentButtons，默认当前生效档）；简化模拟渲染预览；[更换背景图片] [缺省背景] [取消] | 切日/夜→重渲染；换图；缺省（目标档回退）；取消 |
| S1 选图 | 系统图片选择（Photo Picker / GetContent `image/*`） | 取消→回 S0 |
| S2 裁剪 | 原图 + 键盘宽高比范围框（可拖动、双指等比缩放），显示目标档/比例；[取消] [裁剪完成] | 手势；完成→进 S3 |
| S3 应用预览 | 回到 S0 布局 + 预览已用裁切图重渲染，**新增 [确认应用]** | 确认→写盘（§4.6）；继续换图→S1；取消→回 S0（不写盘） |

说明：

- “日/夜”切换只决定**写入哪个档**，不切换系统主题模式（不触发 `followSystemDayNight`）；
- 简化模拟渲染：在编辑器内用当前 `Theme` + 当前配色数据绘制近似键盘（键帽网格、候选
  栏、背景区域），无需离屏抓真机 InputView；
- 「缺省背景」对应当前目标档：删 customization key + 删槽位文件并重渲染预览。

### 4.3 裁剪比例依据

- 比例 = 当前主题键盘**背景实际渲染区域**宽高比（即 `keyboardBackground` 在
  `InputView` 里的可视矩形，随键盘/候选栏布局而定），首版固定取竖屏当前档尺寸；
  横竖屏差异不分别定制。

### 4.4 确认写盘（S3 → 落地）

1. 裁切结果编码为 PNG，写 `<workspace>/backgrounds/custom_<scheme>_<mode>.png`
   （覆盖旧槽位，见 §3.6）；
2. 更新 `<workspace>/customization.yaml`（原子写）；
3. 内存刷新：
   - `ThemeManager`：从 workspace 重载主题（`PackageThemeLoader.load` + 现有
     apply 入口），或最小化路径——更新 `ColorManager` 当前 scheme×mode 的
     `keyboard_background` + `rebuildResolvedPalette`；
   - evict 对应槽位文件的 bitmap 缓存（key 是绝对路径）；
   - 触发 theme/color change listener 重建输入视图，使运行中的键盘立即生效；
4. 失败回滚与 toast（同 §3.6）。

### 4.5 文案（strings，建议键名）

实现阶段补 `values*/strings.xml`：入口标题/摘要、日间/夜间、更换背景图片、缺省背景、
确认应用、取消、裁剪、图片解码失败等。

---

## 5. Schema 与校验同步更新清单（实现阶段落地）

用户明确要求：“根据我们需要完成的部分，同步更新 schema 定义与 validation”。

| 文件 | 改动 |
|---|---|
| `doc/trime-schema.json` | scheme 名 pattern 需允许 `/`（含 `preset_color_schemes`/`color_schemes` 的 patternProperties，`\w+` → `[\w/]+` 或等价）；补 `customization.yaml` 引用说明 |
| `doc/component-schema.json`（或新增 `doc/customization-schema.json`） | 新增 customization.yaml 结构：`color_schemes.<id>.light/.dark` = 部分 palette（key 任意字符串、值 string），`<id>` 允许 `/` |
| `doc/component-validation-gaps.md` | 把 customization.yaml 纳入“component 之外、app 自有的覆盖文件”校验入口清单 |
| `DefinitionValidator`（Kotlin） | 新增 `validateCustomization(customization, resolvedSections)`：scheme 存在性、档合法性、key 已知性、值按 palette 规则校验、DRAWABLE_KEYS 跳过颜色字面量检查 |
| `ComponentThemeLoader.loadTheme` | resolve 后读并校验 customization.yaml → 归一化扁平 scheme → 深合并（§3.4） |
| `script/color_verifier.py` 等 CLI/CI 校验 | 同步支持 customization.yaml（若 CLI 校验 shipped 包，把 customization 视为“可缺省的应用层”） |
| `BuiltinFallbackColors`/`ThemeColor` | 无需改动（key 已存在） |

已知但本期不修的边界（记录，不扩大范围）：

- `color_verifier.py` / `doc/trime-schema.json` 中 palette 允许任意动态 key 的现状；
- scheme id 含 `/` 在 preference（`normal_mode_color`）与其它 UI 中的展示/存储
  （已可存任意字符串，仅 schema 文档需跟上）。

---

## 6. 验收标准（对照最初五步）

- [ ] 「键盘样式」有入口行，点击打开背景编辑器，展示简化模拟渲染的当前键盘；
- [ ] 编辑器可切日/夜，默认档=系统当前生效档；
- [ ] 「更换背景图片」打开系统选择器；选图后进入裁剪页，范围框=键盘渲染区宽高比，
      可拖动、可双指等比缩放；可取消；
- [ ] 裁剪完成后回编辑器预览，预览使用裁切图；界面出现「确认应用」；
- [ ] 确认后：`backgrounds/custom_<scheme>_<mode>.png` 生成、
      `customization.yaml` 更新、内存配色与运行中键盘刷新；重启后依然生效；
- [ ] 「缺省背景」删除对应 customization key 与槽位文件，预览回退 base；
- [ ] 同一 palette 被多 scheme 引用时不串扰（可加单测）；
- [ ] 无独立 dark 的 scheme 也可独立定制夜间档（单测覆盖归一化）；
- [ ] 未知 scheme/key、非法值、解码失败都有清晰报错且不破坏原状态；
- [ ] schema/校验文档与 Kotlin 校验同步更新（§5 清单逐项落地）；
- [ ] `./gradlew spotlessApply` + 相关单测通过（实现阶段执行）。

---

## 7. 文件改动总览（供实现排期）

- 新增：`customization.yaml` schema/文档、背景编辑器 UI（Activity/裁剪 View/简化预览 View）、
  `ThemeCustomization`（读/校验/深合并/写）或并入 `ComponentThemeLoader`。
- 修改：`ThemeSettingsFragment`（入口）、`ThemePrefs`（如需要）、
  `ColorManager`（刷新辅助）、`ThemeManager`（重载入口）、
  `DefinitionValidator`、`ComponentThemeLoader`、schema/CLI 文档（§5 表）、`strings*.xml`。
