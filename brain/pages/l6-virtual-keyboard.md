---
id: l6-virtual-keyboard
title: "L6 虚拟键盘:条上方浮层 + tab 页 + 上下滑翻页"
category: decision
status: active
tags: [ime, l6, ui, keymap]
created: "2026-10-15T13:39:54"
updated: "2026-10-19T23:49:29"
---

<!-- compiled_truth -->
## L5 面板(原 L6 虚拟键盘;2026-10-19 合并定案)

2026-10-19 用户「L6 和 L5 合并 都作为 L5」「原来的 L5 变为虚拟键盘的一个 TAB 叫做场景」:
原 L6 的 tab 虚拟键盘就是 L5,原 L5 溢出行(L4 装不下的尾巴)变成它的第一个 tab「场景」。
面板仍是**条上方浮层**,但只有**两态** `OFF ↔ L5`(2026-10-16 的三态循环作废)。

### 功能定案

1. **形态 = 条上方浮层** —— 不拉全屏,键盘表面照常用。实现:布局里 `L5PanelView` 是
   `ImeRootView` 的第一个子节点;显隐只由 `state.l5` 非空决定。
2. **内容 = 通用容器 + tab/页**(`L5Ui`)。tabs 依次为 **场景 / 标点 / 技术 / 剪贴板**。
   场景 = 原 L5 溢出行(动态内容);两个字符盘各两页;剪贴板每条一行。
   **不设「控制」tab**(2026-10-19 用户「不需要控制tab」):Tab/Esc/Enter 等控制键
   由物理键或 L3 承担,面板只留字符盘。
   「加 tab / 加页 / 加键」是**内容**,「当前 tab/页、翻页夹取」是**状态机** —— 拆开写,
   加一页不动状态机。
3. **开启 = 仅空闲态**;`Sym+Space` 开,再按一次或 **Esc(Sym+Q = 出厂 L3 的 Q)** 关。
   **关闭不写任何字符。**
4. **翻页 = tab 内上下翻**(方向键 ↑/↓ 与上下滑动),**不跨 tab**;到头不循环。
   左右方向键 ←/→ 切 tab(面板开着时空闲态无候选,←/→ 对候选无意义)。
5. **场景 tab = L4 装不下的尾巴**:`L4PanelView` 量出前缀切点 `cut` → service 转给
   `ImeController.setOverflowCut(cut)` → `l4Keys().drop(cut)` 变成场景键。

### 网格与对齐(2026-10-19 用户定案)

用户原话依次是「L5键盘采用网格布局对齐 有最小网格 内容可跨网格增长」「L5按键有对齐问题 应该对齐到网格」。
落地成 **一套基础网格 + 跨格 + 字居中**:

- 所有 `GRID` tab 共用 `L5Ui.GRID_COLUMNS = 8` 列基础网格;不再给场景 tab 单独改列数
  (此前 5 列会让场景与符号盘的键边界对不上)。
- `L5Key.span` 表示占几格:短键 1 格,`@hotmail.com` 这类长串 2 格,更长的 3 格。
  换行与分页都按格算(`paginateGrid`),长键不会被页边界劈开,也不会因列数变化被裁字。
- 场景 tab 每页两行;内容多了就增加页,上下滑翻页。符号盘出厂页保持各自内容。
- **键帽文字居中**在格心(此前 TextView 默认贴左上角,视觉上像没对齐);剪贴板整行键仍
  左对齐、垂直居中。

### 空 tab / 合法性过滤(2026-10-19 用户定案)

- **没有可显示键的 tab 整个隐藏**,不再画「(无溢出键)」占位页:没有溢出时没有场景 tab,
  剪贴板为空时没有剪贴板 tab。
- **所有 tab 都为空时不进入空面板**:数字类可能既没有场景、字符盘又全被数字白名单
  滤掉(去掉控制 tab 后没有恒保留的功能键 tab);此时 `cyclePanel()` 不切到 L5。
  面板开着时最后一个 tab 消失(如场景尾巴装回 L4)则 `closePanels()`,不留空 `L5Ui`
  (`L5PanelView` 会取 `ui.tab`,空 tabs 取不到)。
- `L5Ui` 的 `allowText` 按当前输入框过滤文本键;功能键(`KeyOut.Special`)不是字符,始终保留。
  密码框只留可打印 ASCII;数字/电话框只留数字与 `FieldProfile.digitChars` 放行字符。
  滤空的页/ tab 一并丢弃。

### L1-L3 去重(2026-10-19 用户定案)

用户原话「L5 不需要 L1-3 已经有的字符」。字符盘与 L4 共用同一把尺:

- `ImeRegistry.producible(layout, language, fullWidth)` 算**当前语言 + 全角开关**下 L1-L3
  真正敲得出的取值;`L5Ui.factory(producible = ...)` 把出厂字符盘里同字的文本键直接滤掉。
  不能用 `KeyLayout.producible()` 的出厂并集:语言层表会随全角开关整体切换,否则会误判。
- 滤空的页 / tab 一并丢弃(与输入框合法性过滤同一套空 tab 规则)。
- **场景 tab 不再重复筛**:它本来就是 `l4Keys()` 的尾巴,registry 已在 L4 前做过同一条去重。
- **剪贴板 tab 不参与这把筛**:剪贴板是整段文本的快捷入口,不是单字符键盘;
  是否可粘贴仍只走输入框的 `isPanelTextAllowed` 兜底。
- 面板开着改「中文全角」开关(状态行)时,`ImeService` 调 `ImeController.refreshL5Content()`:
  重算场景与字符盘,按 tab id / 页号尽量保留当前位置(见 `L5Ui.withTabs`),不把用户弹回 tab 0。
  切语言 / 换输入框仍先收面板(那是新的空闲态)。

### 三条由此确立、别当 bug 改回去的规则

- **`L3(Sym) 空格空闲态被抢走`**:L3 层照可可扩展字符表定,原文写着「空格/回车/删除保持原功能」。
  2026-10-15 空闲态让位给浮层面板(组词中仍是空格)。`ImeControllerTest` 里那条测试已改名
  `L3 空闲态空格让位给面板 组词中仍是空格`。**与可可的对齐就此打破,是定案不是遗漏。**
- **打开/关闭消费一次性 L3**:单击 Sym(一次性借用)+ Space 开面板后,借来的 L3 被这一下
  用掉(`consumeOneShot`)。不还的话开关完面板下一颗字母键会被当成 L3 符号敲出去。
  双击锁 / 长按 Sym 不受影响(不是一次性层)。
- **面板是符号盘**:出厂页全是标点/符号,**没有 a-z**;中文 `composes` 只认 a-z,
  所以面板上点符号 = 直接上屏、**面板留在原地**(好接着点下一颗),组词一开始才收面板。

### 上屏通路:两块面板共用一条

L4 中区 / L5 面板都走 `ImeController.commitL4Key`(键的输出是 `KeyOut` 的事,
不是「长在哪块面板」的事;第二条分支就是第二处会漂移的语义)。视图层 `onKey` 直连
`commitL4Tapped`。剪贴板整行键例外:走 `commitClipboardText`,见 [[clipboard-panel]]。

### 视图与窗口(易漏的集成点)

- **`L5PanelView`**:tab 条 + 键格;GRID tab 用 8 列权重网格,长键按 `L5Key.span` 跨格,
  字居格心;剪贴板用 `ROWS` 整行。上下滑用与候选行同一套 `DragGesture` + `SpringChannel`
  物理;滑动接静默窗 `swipeGate`,**点键不看它**。
- **面板改窗口高度 ⇒ 必须 `notifyInsetsChanged()`**(不报会顶裁);切 tab/翻页会改变面板
  高矮,所以 `setContent()` 返回「是否重建」,服务层在重建后也补报一次(2026-10-19)。
  `onComputeInsets` 与 `ImeRootView.uiTop` 都取 L5 面板顶。
- `btn_l5` **常驻**,**展开/关闭只换颜色、图标形状始终是键盘 `ic_keyboard`**(2026-10-19 用户
  「虚拟键盘图标展开和关闭仅仅改变颜色即可」):OFF 染状态行前景色、L5 染主题 accent;
  旧的收起/展开箭头 drawable(`ic_collapse` / `ic_expand`)已删。

### 验证状态

`L5PanelTest` 与全量 `:app:testDevUnitTest` 全绿(388 条);`:app:assembleDev` 成功。2026-10-19 真机
(TTANCN2100002663)冒烟通过:按钮开合、切 tab、上下滑翻页、点键上屏且面板留原地、
**场景 tab 8 列网格 + 长域名跨 2 格(4 颗/行,`@hotmail.com` 等完整不裁)**、键帽文字居中、
**面板已无控制 tab**;手机号框只剩场景(合法电话区号),密码框只剩技术(ASCII);
**L1-L3 去重:全角态标点页只剩 `； … — 「 」 “ ” ·` 等 L3 敲不出的字,切半角后
`， 。 ！ ？ ： （ ） 《 》` 等回到面板,且面板开着切换时原地刷新**;**数字框没有可显示键时
点键盘图标不打开空面板、不崩**;**键盘图标开/关只变颜色(键盘→accent),形状不变**;
剪贴板空态、面板 Esc 关(物理 Sym+Q)、组词开始收面板。
`Sym+Space` 未用 adb 复现(`adb input keyevent 63` 会拉系统输入法选择器),由 JVM 覆盖;
剪贴板非空条目点按、场景 tab 开着时换框/换层的原地刷新仍未真机覆盖。详见 [[pending-todos]]。


## Timeline

- time: 2026-10-15T13:39:54
  kind: decision
  summary: "Created this page: L6 虚拟键盘:条上方浮层 + tab 页 + 上下滑翻页"
  source: created via brain create-page
  affects: [l6-virtual-keyboard]

- time: 2026-10-15T13:40:12
  kind: decision
  summary: "L6 虚拟键盘:L4/L5/L6 三块面板共用 commitL4Key 上屏通路;状态机在 L6Ui,视图在 L6PanelView"
  source: brain update-truth
  affects: [l6-virtual-keyboard]

- time: 2026-10-16T03:19:42
  kind: reversal
  summary: "2026-10-16 三态化(reversal):L6 的开关从「sym+space 二态」改成**三态循环** OFF→L5→L6→OFF。用户原话「把 L5 的图标的工作改为 L5 L6 和关闭状态的切换 sym+space 的功能和切换图标的功能一致 默认状态不显示 L5 和 L6」。要点:① PanelMode(OFF/L5/L6) 进 ImeState,ImeController.cyclePanel() 是唯一推进入口, sym+space 与状态行右区 btn_l5 走同一通路;② L5 行/按钮的显隐与图标从视图层变量(l5RowShown)收进 state.panelMode,btn_l5 **常驻**(不再「有溢出才显示」),图标随态(展开箭头 ic_expand / 键盘 ic_keyboard / 收起箭头 ic_collapse),非 OFF 态用主题 accent;③ 组词开端/换框/切语言/窗口收起/Esc 全走 ImeState.closePanels();④ 旧「sym+space 二态开关 L6」的定案作废。附带:连续敲 Sym 三次会撞双击窗(单击借 L3 / 双击锁 L3 / 再敲取消),所以三态循环的单测用 cyclePanel() 直测,不连敲三次 symSpace。"
  source: "2026-10-16 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T19:48:49
  kind: decision
  summary: "2026-10-19 合并后重写:L6 虚拟键盘与原 L5 溢出行合并为一块两态 L5 面板,场景为第一个 tab"
  source: "2026-10-19 用户合并定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T19:48:58
  kind: reversal
  summary: "2026-10-19 合并(reversal):L6 虚拟键盘与原 L5 溢出行合并为一块 L5 面板。用户原话「L6和L5合并 都作为L5」「原来的L5变为虚拟键盘的一个TAB 叫做场景」。要点:① 面板两态 OFF↔L5(2026-10-16 三态作废);② 原 L6 tab 键盘即 L5,原 L5 溢出行成为第一个 tab「场景」(L4 装不下的尾巴,8列×16颗/页);③ 其余 tab 标点/技术/控制/剪贴板;④ 视图 L6PanelView→L5PanelView,状态 L6Ui→L5Ui、state.l6→state.l5、l6Open→l5Open;⑤ 旧 L5 独立行与分割线删除,btn_l5 常驻,OFF 展开/L5 收起;⑥ 场景内容由 L4 onOverflowChange 的 cut 喂 setOverflowCut。"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard clipboard-panel pending-todos]

- time: 2026-10-19T20:47:34
  kind: decision
  summary: "2026-10-19 真机冒烟(TTANCN2100002663):L5 面板按钮开/关、场景/标点/控制/剪贴板切 tab、标点与控制上下滑翻页、点键上屏面板留原地、场景 tab 显示 L4 邮箱域名溢出、剪贴板空态、Esc 键关、组词开始收面板均通过。冒烟发现 8 列会把 @hotmail.com 裁成 @hotmail,场景 tab 改为 5 列 10 颗/页(SCENE_COLUMNS=5)后完整显示;符号盘仍 8 列。Sym+Space 双入口未用 adb 复现(adb input keyevent 63 会拉系统输入法选择器),由 L5PanelTest 覆盖。"
  source: "2026-10-19 真机冒烟"
  affects: [l6-virtual-keyboard pending-todos]

- time: 2026-10-19T20:54:51
  kind: decision
  summary: "2026-10-19 真机冒烟后校正:场景 tab 5 列/10 颗,切 tab 高度变化补 notifyInsetsChanged,验证状态更新"
  source: "2026-10-19 真机冒烟收尾"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T22:43:32
  kind: decision
  summary: "2026-10-19 网格定案:所有 GRID tab 共 8 列基础网格,长键按 span 跨 2-3 格,键帽文字居中;场景不再单独 5 列"
  source: "2026-10-19 用户「网格布局 + 内容跨网格 + 对齐到网格」"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:00:53
  kind: decision
  summary: "2026-10-19 用户「不需要控制tab」:出厂 tabs 改为 场景/标点/技术/剪贴板;控制键走物理键或 L3"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:19:14
  kind: decision
  summary: "2026-10-19 用户「L5 不需要 L1-3 已经有的字符」:字符盘按 ImeRegistry.producible 去重,并随全角开关原地刷新"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:21:43
  kind: reversal
  summary: "2026-10-19 用户「不需要控制tab」(reversal):撤掉此前「标点/技术/控制/剪贴板」里的控制 tab;Tab/Esc/Enter/Del/Home/End/PgUp/PgDn 不再做成面板键,改由物理键或 L3 承担。面板 tabs 现为 场景/标点/技术/剪贴板。"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:33:46
  kind: decision
  summary: "2026-10-19 补空面板守卫:所有 tab 都为空时不进入 L5,面板开着时最后一个 tab 消失则收起"
  source: "2026-10-19 数字类冒烟 + L5PanelTest"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:49:29
  kind: decision
  summary: "2026-10-19 用户「虚拟键盘图标展开和关闭仅仅改变颜色即可」:btn_l5 图标形状恒为键盘,仅切换 tint;删除 ic_collapse/ic_expand"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard]
