# Handoff — T9 拼音拆解面板：collapse/排序 现状与待办（2026-09-03）

> 给新 session 的交接文档。读完本文件再动代码；可参考
> `doc/repo-knowledge.md`（架构总览）与 `doc/t9-toolbar-plan.md`（功能背景）。

## 0. 仓库 / Git 状态

- remote: 唯一 `origin` = Gitea `Home/trime`（192.168.1.50:3000，SSH key
  `/ssh-keys/gitea-dsh-dev-docker` 已配好）。main 已开放直推。
- 当前 main 最新：`f53d95bf fix(ime): collapse T9 panel state after picks in the unrolled window`
  （此前一串已合入：#10 隐藏覆盖列、#11 versionName 3.4.1、#12 数据+重启重载、
  #13 面板滚动回顶、collapse、排序）。
- 工作树干净（本地可能多一个未推送分支，见下）。
- **v3.4.1 tag** 指向 main 最新；每次修完按用户习惯要 `git tag -f -a v3.4.1 HEAD && git push --force origin refs/tags/v3.4.1` 触发 release-ci 出包测试。

## 1. 待办（当前任务，未完成）

**问题**：用户在 **unrolled 候选窗**（点开的大候选列表）里点选一个词组后，
T9 拼音拆解悬浮面板 **没有折叠到剩余部分**——仍显示整串开头第一个音节的选项。
compact 候选条点选则已正常（collapse 生效）。

**注意**：collapse 机制本身有效（compact 场景验证过：flypy `8428569473 → 9473`、
full `…9264736`），问题只出在 **unrolled 路径**。

## 2. 已查明的机制（很重要，别推翻重来）

- 输入走 T9 九键（full=全拼九键 `wanxiang_t9` / flypy=小鹤双拼九键
  `wanxiang_flypy_t9`）。面板驱动靠控制器自有状态 `lastDigits`（用户按过的数字串）
  + `confirmed`（面板点选确认的音节，含 code/consumed），再 `setInput` 喂回 Rime。
- **librime `RimeSelectCandidate → Context::Select` 只把候选段标 kSelected（上屏字并入
  composition preedit），并不会缩短 context raw input** → 面板曾试过的
  「读 getRawInput() 比对/采纳」方案（两版，含 crash 版）**从根上不可行**，已回退。
- 正确信号：composition 消息里的 **preedit + selStart..selEnd**。librime
  `GetPreedit` 把已转换段渲染成候选文字，**最后一段（未确认剩余）的起点正是
  selStart**（`app/src/main/jni/librime/src/rime/composition.cc` 的 GetPreedit 循环）。
  实测：`preedit='治不了yi ren‸' sel=3..9`、`raw 仍=8428569473`。
- 当前实现（main `f53d95bf`）：`T9DisambiguationController.onCompositionUpdate`
  与 `attach()` 里调用 `collapseToEngineRemaining(data)`：
  - 取 `preedit[selStart..selEnd]` 的拼音尾巴 → 剥声调 → 按空格/贪婪分词 → 用**自带
    音节表**折回数字（full 用 t9_code，flypy 用 flypy_t9_code）→ 得到剩余数字串；
  - 若该串是 `lastDigits` 的**真后缀** → 塌缩 `lastDigits=剩余、confirmed清空`，
    面板从剩余开头继续；解析失败/非后缀 → 不动（有保护）。
- 排序（已合 main，`5161f335`）：decodeLeading 候选按**全拼字符串长度降序、等长按拼音
  升序**（原按 consumed 位数；flypy 每音节固定 2 位导致退化成纯字母序，用户不满）。

## 3. unrolled 为什么不行（进展与假设）

- unrolled 候选窗会**顶掉键盘窗**：`KeyboardWindow.onDetached → controller.detach()`
  （拆面板但保留 owned 状态）→ 选词产生的 composition 更新到不了控制器 →
  回到键盘窗 `attach()` 时补做 collapse 是本轮已合入的修法（f53d95bf），但**实测仍不生效**。
- 新日志（**注意：以下来自诊断分支，main 上没有**）：

  诊断分支 `fix/t9-diagnose-unrolled`（本地有提交 `68f19cb9`，含两行附加日志：
  attach 时打 preedit/sel/owned + 异步打 engine raw）。**尚未让用户用这版出包复测**。
  上一版（无日志）日志显示：

  ```
  folded tail 'geng feng fu cha lian meng' -> '443438425664' is not a suffix of owned
  '84285694734384285664'; not collapsing
  refreshPanel raw='84285694734384285664' … segments=[(shang,2)…]
  ```

  即：unrolled 选词后，preedit 剩余尾巴折出的数字 **不是** 我们 owned 数字串的后缀
  → collapse 保护拒绝执行 → 面板停留在最开头。**这与 compact（剩余=后缀）行为不同**。

- 两条候选假设（需诊断日志判定，**别盲改**）：
  a) unrolled 点选是「真上屏」：引擎 raw 缩短为剩余纯数字 → 那就该在 attach 时
     **直接采纳 engine raw**（若纯数字且≠owned），而不是折叠 preedit 拼音；
  b) 引擎 raw 仍全串、composition 结构更复杂（如剩余段不在末尾 / sel 只框住最后一个段）
     → 需再研究 GetPreedit 多段结构。
  请新 session 先让用户用 `68f19cb9`（fix/t9-diagnose-unrolled）构建 debug，复现
  unrolled 点选，抓这两行：
  `adb logcat -d | grep -E "t9diag: attach|t9diag: engine raw"`，
  看 `attach engine raw='…'` 是缩短还是全串，再按 a/b 定方案。

## 4. 相关代码索引

- `app/src/main/java/com/osfans/trime/ime/disambiguation/T9DisambiguationController.kt`
  —— onCompositionUpdate / attach/detach / collapseToEngineRemaining / pinyinTailToDigits
  / stripToneMarks / segmentPinyin / refreshPanel / onPick / feedMixedComposition。
- `.../ime/disambiguation/T9PinyinDecoder.kt` —— decodeLeading（排序在 return 处，
  按 `pinyin[0].length` 降序 + pinyin 升序）。
- `.../ime/disambiguation/T9DisambiguationPanel.kt` —— ListAdapter+DiffUtil（防
  notifyDataSetChanged 崩溃）+ 主线程 post + submit 后 scrollToPosition(0)。
- `.../ime/keyboard/KeyboardWindow.kt` —— attachKeyboard/onAttached/onDetached 挂面板。
- librime（**子模块，别改**，只读参考）：`app/src/main/jni/librime/src/rime/context.cc`
  `Select()`、`composition.cc` `GetPreedit()`。
- 单测：`app/src/test/.../ime/disambiguation/T9PinyinDecoderTest.kt`（含 flypy 排序断言）。

## 5. 编译 / 测试

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home" ANDROID_USER_HOME="$PWD/.android-home"
./gradlew --offline :app:compileDebugKotlin          # ~40s
./gradlew --offline :app:testDebugUnitTest --tests "com.osfans.trime.ime.disambiguation.*"
```
（容器 gradle 离线即可，勿开 spotless，用户已多次说跳过。）

## 6. 与用户的协作节奏

- 用户在手机上用 **debug 包 + adb logcat**（`com.osfans.trime.debug`）测试，能贴
  `t9diag` 日志。t9diag 日志为 Timber.d，**release 树只打 INFO+**，所以必须 debug 包。
- 改动请带足够日志，先确认再大改；用户对「反复猜+白改」很不满。
- 合入后按用户惯例把 v3.4.1 tag 移过来触发 release，再让用户装 release 复测。

## 7. 已知小坑 / 约定

- `onCompositionUpdate` 里 PopupCandidatesMode.ALWAYS_SHOW 时 composition 会被清空
  转发（InputView 逻辑），注意别依赖该消息。
- collapse 只在 `confirmed.isEmpty()` 且无自身 feed 竞态时安全（有 feedPending 保护，
  但那套 reconcile 已回退，勿重复引入）。
- 文件路径含中文包名时 git 命令注意引号。
