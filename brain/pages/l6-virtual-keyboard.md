---
id: l6-virtual-keyboard
title: "L6 虚拟键盘:条上方浮层 + tab 页 + 上下滑翻页"
category: decision
status: active
tags: [ime, l6, ui, keymap]
created: "2026-10-15T13:39:54"
updated: "2026-10-16T03:19:42"
---

<!-- compiled_truth -->
## L6 虚拟键盘(2026-10-15 用户定案,同一次对话里问定)

用户原话:「空闲状态 sym+space 打开虚拟键盘,这是 L6 键盘,支持多个 tab 页和上下滑动虚拟键盘翻页」。

### 四条功能定案

1. **形态 = 条上方浮层** —— 不拉全屏,键盘表面照常用。实现:布局里 L6PanelView 是
   ImeRootView 的**第一个子节点**(盖过 L5 行),显隐只由 `state.l6` 非空决定。
2. **内容 = 通用容器 + 出厂页**(L6Ui)。出厂三个 tab:标点 / 技术 / 控制,每 tab 两页。
   「加 tab / 加页 / 加键」是**内容**,「当前 tab/页、翻页夹取」是**状态机** —— 拆开写,
   加一页不动状态机。
3. **开启 = 仅空闲态**;`Sym+Space` 开,再按一次或 **Esc(Sym+Q = 出厂 L3 的 Q)** 关。
   **关闭不写任何字符。**
4. **翻页 = tab 内上下翻**(方向键 ↑/↓ 与上下滑动),**不跨 tab**;到头不循环。
   左右方向键 ←/→ 切 tab(从「不跨 tab」推出 —— 切 tab 得另有一条,而面板开着时空闲态
   无候选,←/→ 对候选无意义)。

### 三条由此确立、别当 bug 改回去的规则

- **`L3(Sym) 空格空闲态被抢走`**:L3 层照可可扩展字符表定,原文写着「空格/回车/删除保持原功能」。
  2026-10-15 空闲态让位给 L6(组词中仍是空格)。`ImeControllerTest` 里那条测试已改名
  `L3 空闲态空格让位给 L6 组词中仍是空格`。**与可可的对齐就此打破,是定案不是遗漏。**
- **打开/关闭消费一次性 L3**:单击 Sym(一次性借用)+ Space 开面板后,借来的 L3 被这一下
  用掉(`consumeOneShot`)。不还的话开关完面板下一颗字母键会被当成 L3 符号敲出去。
  双击锁 / 长按 Sym 不受影响(不是一次性层)。
- **面板是符号盘**:出厂页全是标点/符号/功能键,**没有 a-z**;中文 `composes` 只认 a-z,
  所以面板上点符号 = 直接上屏、**面板留在原地**(好接着点下一颗),组词一开始才收面板。

### 上屏通路:三块面板共用一条

L4 中区 / L5 尾巴行 / L6 键盘都走 `ImeController.commitL4Key`(键的输出是 `KeyOut` 的事,
不是「长在哪块面板」的事;第二条分支就是第二处会漂移的语义)。视图层 `onKey` 直连
`commitL4Tapped`。

### 视图与窗口(易漏的集成点)

- **L6PanelView**:tab 条 + 键格(每行 8 颗),上下滑用与候选行/L5 同一套 `DragGesture` +
  `SpringChannel` 物理;滑动接静默窗 `swipeGate`,**点键不看它**。
- **面板改窗口高度 ⇒ 必须 `notifyInsetsChanged()`**(与 L5 同一条理由,不报会顶裁);
  `onComputeInsets` 与 `ImeRootView.uiTop` 都要把 L6 排在 L5 之前(它是最高一层)。

### 验证状态

状态机 16 条 JVM 单测(`L6KeyboardTest`)全绿;`assembleDebug` 过;**视图的真机冒烟欠账**
(开合 / 点键上屏 / 上下滑翻页 / 切 tab)见 [[pending-todos]]。


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
