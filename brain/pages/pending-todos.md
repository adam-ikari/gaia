---
id: pending-todos
title: "待办清单(2026-10-12 收尾时点)"
category: project
status: active
tags: [todo, backlog]
created: "2026-10-12T16:55:18"
updated: "2026-10-15T13:41:16"
---

<!-- compiled_truth -->
## 待办(2026-10-15 更新,按价值排序)

### 0. L6 虚拟键盘:视图真机冒烟待补(2026-10-15 新增)

- **已过**:状态机 16 条 JVM 单测(`L6KeyboardTest`)全绿;`assembleDebug` 过;APK 里布局与
  类都在(`aapt2 dump xmltree` 见 `L6PanelView` 是 `ImeRootView` 第一子节点;dex 里有该类)。
- **待做(本机无真机、无 `/dev/kvm`,只能真机跑)**:空闲态 `Sym+Space` 开合面板、
  **点面板键上屏**、上下滑翻页、切 tab、Esc(Sym+Q)关面板。
- **E2E 场景 `l6-keyboard` 已写好**(`tools/aitest/scenarios.mjs`),**尚未跑过** —— 跑通前
  别当成已验。
- **设计见** [[l6-virtual-keyboard]]。

### 1. 长按键盘 L2 符号上屏(用户 2026-10-14 给的需求,未开工)

- **原文**:「长按键盘L2符号上屏」。
- **现状**:L2 符号走 Alt 层(单击一次性 / 双击锁定 / 长按按住期间生效),没有"长按字母键直接出该键 L2 符号"这条路。
- **待澄清(开工前问用户)**:触发路径是哪种 ——
  (a) L1 态**长按字母键**直接上屏该键的 L2 符号(长按 L → `'`)?
  (b) 长按 Alt 期间按某键**松手即上屏**(已是当前行为)?
  (c) L2 态(已借/锁 Alt)长按 = 连续上屏该符号?
- **注意**:长按现在有既定语义(Alt/Sym/Shift 长按 = 按住期间生效),新语义要与
  [ModifierGestures] 的敲击/长按窗口协调,别破坏五键选字(Alt+字母 = 选字)——
  实测 `input keycombination Alt+L` 会被五键选字吃掉,手动分节符在本机不可达同源。

### 2. librime `0d00afba`(emoji 词典,默认关)设备冒烟待补

- **已过**:fetch/diff 审查、双 ABI 构建、JVM 单测、提交 `c313083` 已推。
- **待做**:候选/上屏/自动分隔符三项冒烟(emoji 默认关,预期行为不变)。
- **附**:2026-10-14 已在新 librime 上验证过分隔符翻页跟随(写入 ni'hao/nihao 实机日志),
  三项完整冒烟仍欠一次。

### 3. `rawAfterConsuming` 撇号坐标:文档与实现矛盾,raw 含 `'` 时可能截错缓冲

- **事实**:`RimeEngine.query` 重建时**跳过 `'` 不喂引擎**(代码);而 `RimeEngine.rawAfterConsuming` 的注释、`ImeController.readRemainder` 的注释(「引擎坐标含 '」)都说撇在喂进去的串里,依据是宿主机探针(绕过 skip 直接喂)+ E2E(`nihao` 无撇,两种坐标语义巧合一致)。
- **风险**:用户若能输入 `'`(本机只能 hold-Alt+L,`input keycombination` 会被五键选字吃掉 —— 实测),选词后剩余缓冲可能被截错位。
- **修法方向**:实现改成数非撇字符的映射(`ImeController.displaySpelling` 已按此实现,可对齐),并把三处注释改成同一个口径;**先在真机证实用 hold 方式敲出的 `'` 进入 raw 后的实际消费值**,再动实现(教训见 [[unverified-as-fact]]:探针证据不等于生产证据)。

### 4. `five-key-slots` E2E 断言脆弱:三次查询候选序随词频漂移

- **现象**:断言要求 Shift/Alt/Ctrl 三键上屏**互异的词**;引擎候选序在查询间重排。
- **证据**:select 埋点显示三键 zone 全部正确(0/4/3),Ctrl 选的就是它槽位当时显示的词 —— 功能无 bug,是断言假设了候选序不变。
- **修法方向**:把断言改成**不变量**——从 select 日志解析每键的 zone(Shift=0/Sym=1/空格=2/Ctrl=3/Alt=4)与 consumed 映射,不再比词面。

### 5. 调出即初始态的语言口径待确认

- **已做**:层/大写/修饰内部态每次 onStartInput 全量复位(2026-10-12 用户定)。
- **未动**:语言仍按旧规则(换框恢复 fieldProfile 默认;`restarting=true` 的**同框重启不动**)。

### 已完成(不再追)

候选词槽内截断、候选栏箭头贴边+触摸 40px、五槽统一内缩(分栏仍 5 均分)、上滑改点选、拼音自动分隔符(翻页跟随 + 每段边界补撇)、拼音气泡 16sp、调出即初始态、评审修复(spelling 随组词一起清)、librime 两次更新。


## Timeline

- time: 2026-10-12T16:55:18
  kind: decision
  summary: "Created this page: 待办清单(2026-10-12 收尾时点)"
  source: "2026-10-12 收尾"
  affects: [pending-todos]

- time: 2026-10-12T16:57:55
  kind: decision
  summary: "建立待办清单:撇号坐标矛盾(探针≠生产)、five-key-slots 断言脆弱改 zone 不变量、调出复位的语言口径待确认"
  source: "2026-10-12 收尾"
  affects: [pending-todos]

- time: 2026-10-13T16:52:13
  kind: decision
  summary: "2026-10-13 追加第 0 项:librime 0d00afba 设备冒烟待补(USB 掉线+焦点异常卡住,构建单测已过)"
  source: "2026-10-13 收尾"
  affects: [pending-todos]

- time: 2026-10-14T12:39:45
  kind: decision
  summary: "2026-10-14:追加第 1 项长按 L2 符号上屏(未开工,触发路径待澄清);分隔符翻页跟随+边界补撇已完成"
  source: "2026-10-14 评审+用户纠正"
  affects: [pending-todos]

- time: 2026-10-15T13:41:16
  kind: decision
  summary: "2026-10-15:追加第 0 项 —— L6 虚拟键盘视图真机冒烟待补(状态机单测+打包已过,E2E 场景已写未跑)"
  source: brain update-truth
  affects: [pending-todos]
