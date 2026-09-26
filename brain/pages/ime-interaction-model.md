---
id: ime-interaction-model
title: "三层键盘与选字/翻页交互模型（模仿可可）"
category: decision
status: active
tags: [ime, ux, keymap]
created: "2026-09-23T04:27:03"
updated: "2026-09-23T04:29:42"
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

## 锁定语义（单击 = 一次性，双击 = 锁定）

- `Alt` 单击 → 进入 L2 **一次性**：下一个按键输出符号后自动回 L1。
- `Alt` 双击（间隔 < 400ms）→ **锁定 L2**，连续出符号，再单击 `Alt` 解锁回 L1。
- `Sym` 单击 → L3 一次性；`Sym` 双击 → **锁定 L3**（即"Sym 锁定切换三层键盘"）。
- 已锁定 L2 时单击 `Sym` → 直接切到 L3 锁定，反向同理 → 锁定态下 Alt/Sym 互为层切换键。
- `Sym` 三击 → L1→L2→L3 循环（给不想记双击的人）。
- `Shift` 单击 = 下一键大写，双击 = Caps 锁定（与系统一致）。
- **按住 `Sym` 再按其他键 = Ctrl 组合**（复制/粘贴/剪切/全选/撤销/重做），松开即失效——与可可"sym 键同时当 ctrl 键"一致；这也顺带给出 vim 的 `Ctrl+[` = ESC。
- `Shift+Space` = 中英切换（可可语义）；`Shift+Enter` = 快捷发送（可选、默认关，需无障碍）。

## 选字通道（5 个候选位）

1. **屏幕候选栏**：点击候选直接上屏；**在候选上向上滑动 = 选字上屏**；**在候选栏左右滑动 = 翻页**；左右箭头可点。
2. **软键盘区域上滑（屏幕版飞字）**：键盘横向按 `1:2:4:2:1`（默认）或 `2:3:4:3:2` 分成 5 区，从键盘面向上滑动 = 选中该区候选——这是"上滑选字"在 TT2 上的落地形态。
3. **物理键**：`空格` = 第 1 候选；`Ctrl(Fn)+q/w/e/r/t` = 第 1..5 候选（可配置到别的键）；`Tab`/`DPAD_DOWN` = 高亮下移，`DPAD_UP` = 确认高亮（上滑选字的键盘等价）；`DPAD_LEFT/RIGHT` = 翻页。

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
