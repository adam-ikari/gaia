---
id: ime-interaction-model
title: "三层键盘与选字/翻页交互模型（模仿可可）"
category: decision
status: active
tags: [ime, ux, keymap]
created: "2026-09-23T04:27:03"
updated: "2026-09-23T04:29:42"
updated: "2026-09-27T22:55:16"
tags: [ime ux keymap]
---


<!-- compiled_truth -->
模仿可可的交互模型，并按 [[tt2-hardware-constraints]] 重新排布可行通道。总原则：**每个动作至少两条通道（物理键 + 屏幕手势）**。

## 三层键盘

| 层 | 进入方式 | 内容 | 默认映射要点 |
|---|---|---|---|
| L1 基础层 | 默认 | 字母、空格、回车、删除 | 中文态走拼音，英文态直接上屏 |
| L2 符号数字层 | `Alt` | 数字与键帽上档符号 | `q..p = 1234567890`；`a s d f g h j k l ; = ! @ # $ % ^ & * ( )`；中文态输出全角、英文态半角（可配置） |
| L3 编程层 | `Sym` | 每键输出自定义字符串或功能键 | 见下方预置映射 |

**L3 编程层预置**（第 1/2/3 行字母键位上放开发高频符号，位置刻意做成"成对括号相邻"）：

```
q {   w }   e [   r ]   t (   y )   u <   i >   o "   p '
a :   s ;   d =   f +   g -   h *   j /   k _   l |
z \   x `   c &   v %   b #   n @   m $   , ^   . ~   / ｜(管道)
```

空格在 L3 输出 `->`，回车仍为回车，删除仍为删除；`z/x` 组合可再放 `=>`、`!=`、`==`。每个键都可在设置里改成任意字符串或功能键（ESC/TAB/方向键/F5…），与可可"扩展键盘"同构。

## 修饰键语义：敲击 = 记忆动作，长按 = 临时层

总纲（2026-09-27 定案，`ImeController.HOLD_MS = 300ms`）：**"敲击"落状态，"长按"只在按住期间生效，松开即取消**；两者共用一次按下/松开，不互相打架。

- **长按 `Alt` = 按住 L2、长按 `Sym` = 按住 L3**：按下即进层，按住期间可连续出符号；松开时恢复按下前的 `lock`/`oneShot` 快照（= 取消）。长按期间按下任何其他键 = 这次长按已被"用掉"，松开必然取消。
- **`Alt` 单击** → L2 一次性（keyDown 生效）；**双击**（TapTracker 200ms 窗口）→ 锁定 L2；**再单击**解锁。双击刚建立的锁定不会被紧随的长按取消（收尾时保留 `holdTapN >= 2` 的状态）。
- **`Sym` 单击** → L3 一次性、**双击**锁定、**三击** L1→L2→L3 循环；敲击语义一律在 keyUp 判定（快速松开才算敲击）。
- 锁定态下 `Alt`/`Sym` 单击异层键 = 直接换锁（互为层切换键）。
- **`Shift`**：单击 = 大写一次（输入一个字母后自动取消；已在 Caps 态时单击 = 关掉锁定）；双击 = Caps 大写锁定；**长按 = 按住期间大写**——大写来自物理修饰键本身，松手即取消、不留任何状态。状态行有大写芯片提示：Caps = 实心，一次性 / 按住中 = 描边。把 Shift 当修饰键用过（按住期间按了别的键）就不算敲击，不会误武装"大写一次"。
- **按住 `Sym` 再按其他键 = Ctrl 组合 —— 已废弃（2026-09-27 反转）**：Sym 长按专用于进 L3；Ctrl 组合只由 `Ctrl/Fn` 键承担（`Ctrl+q/w/e/r/t` 五键选字），vim 的 `Ctrl+[`=ESC 走这条路。
- `Shift+Space` = 中英切换（可可语义）；`Shift+Enter` = 快捷发送（可选、默认关，需无障碍）。

## 选字通道（5 个候选位）

1. **屏幕候选栏**：点击候选直接上屏；**上滑 = 选字上屏**；**左右滑 = 翻页**；两侧箭头可点；长按候选删除该词的用户频次。
2. **键盘表面飞字（TOUCHPAD 5 区）**：组词中窗口临时全屏接住键盘表面触摸，按起点横坐标平分 5 区上滑选字。
3. **物理键**：`空格` = 第 1 候选；`Ctrl(Fn)+q/w/e/r/t` = 第 1..5 候选（可配置到别的键）；`Tab`/`DPAD_DOWN` = 高亮下移，`DPAD_UP` = 确认高亮；`DPAD_LEFT/RIGHT` = 翻页。

**上滑选字的手感（两条上滑通道共用同一套动画）**：

- **拖动即反馈**：一开始上滑，被选的词就随手指缓慢起升（0→26dp），同一行的其他候选同步渐隐（到阈值时剩 0.4）；
- **越过阈值才提交**：位移达到阈值立刻触发选字，完成段动画接管（词加速飞到 64dp 并淡出，其余候选加速淡完）；松手还没过阈值就把拖出来的效果淡回原位。
- **触发距离取保守档**：阈值档位由 15/25/40 整体上调为 **25/40/60，默认 40**（设置页"飞字触发滑动距离"）。老取值一次性迁移（pref 标记 `fly_threshold_v2`），不带标记的话已被迁移过的值会被反复上调。

## 翻页通道

候选栏左右滑、`DPAD_LEFT/RIGHT`（翻屏助手双击编辑模式后滑动）、候选框两侧箭头、可选音量键。每页 5 条，页码显示在候选栏中部。

## 时间线

- time: 2026-09-23T04:32:00
  kind: decision
  summary: 三层键盘 = L1 字母 / L2 符号数字(Alt) / L3 编程(Sym)，单击一次性、双击锁定、锁定态互切
  source: 用户需求"sym锁定切换三层键盘"+ 可可 alt 单双击锁定语义
  affects: [tt2-hardware-constraints]
- time: 2026-09-23T04:33:00
  kind: decision
  summary: 上滑选字/左右滑翻页落地为"屏幕候选栏手势 + 软键盘 5 区上滑 + 方向键"三条通道，放弃 TT2 键盘飞字
  source: [[tt2-hardware-constraints]]
  affects: [tt2-hardware-constraints, reference-cocoime]


## Timeline

- time: 2026-09-23T04:27:03
  kind: decision
  summary: "Created this page: 三层键盘与选字/翻页交互模型（模仿可可）"
  source: "用户需求 + 可可拼音输入法使用帮助"
  affects: [ime-interaction-model]

- time: 2026-09-23T04:29:42
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [ime-interaction-model]

- time: 2026-09-27T22:06:58
  kind: decision
  summary: "组词拼音由输入法悬浮条渲染(部分 App 如 Termux 不渲染 composing 区),浮在候选条上方 6dp 缝、不占 insets"
  affects: [ime-interaction-model]

- time: 2026-09-27T22:44:19
  kind: decision
  summary: "修饰键语义重做:Alt/Sym 长按=临时层(松开取消),Shift 单击/双击/长按大写+状态芯片;废弃 Sym=Ctrl 组合(Ctrl 只走 Fn/Ctrl 键);上滑选字改拖动反馈+越阈值提交,飞字阈值档位上调为 25/40/60 默认 40"
  source: "用户需求+实现"
  affects: [ime-interaction-model]
