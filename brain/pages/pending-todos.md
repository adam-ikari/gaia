---
id: pending-todos
title: "待办清单(2026-10-12 收尾时点)"
category: project
status: active
tags: [todo, backlog]
created: "2026-10-12T16:55:18"
updated: "2026-10-12T16:57:55"
---

<!-- compiled_truth -->
## 待办(2026-10-12 收尾时点,按价值排序)

### 1. `rawAfterConsuming` 撇号坐标:文档与实现矛盾,raw 含 `'` 时可能截错缓冲

- **事实**:`RimeEngine.query` 重建时**跳过 `'` 不喂引擎**(代码);而 `RimeEngine.rawAfterConsuming` 的注释、`ImeController.readRemainder` 的注释(「引擎坐标含 '」)都说撇在喂进去的串里,依据是宿主机探针(绕过 skip 直接喂)+ E2E(`nihao` 无撇,两种坐标语义巧合一致)。
- **风险**:用户若能输入 `'`(本机只能 hold-Alt+L,`input keycombination` 会被五键选字吃掉 —— 实测),选词后剩余缓冲可能被截错位。
- **修法方向**:实现改成数非撇字符的映射(`ImeController.displaySpelling` 已按此实现,可对齐),并把三处注释改成同一个口径;**先在真机证实用 hold 方式敲出的 `'` 进入 raw 后的实际消费值**,再动实现(教训见 [[unverified-as-fact]]:探针证据不等于生产证据)。

### 2. `five-key-slots` E2E 断言脆弱:三次查询候选序随词频漂移

- **现象**:断言要求 Shift/Alt/Ctrl 三键上屏**互异的词**;引擎候选序在查询间重排(2026-10-12 两次全量:一次红、一次绿)。
- **证据**:select 埋点显示三键 zone 全部正确(0/4/3),Ctrl 选的就是它槽位当时显示的词 —— 功能无 bug,是断言假设了候选序不变。
- **修法方向**:把断言改成**不变量**——从 select 日志解析每键的 zone(Shift=0/Sym=1/空格=2/Ctrl=3/Alt=4)与 consumed 映射,不再比词面。

### 3. 调出即初始态的语言口径待确认

- **已做**:层/大写/修饰内部态每次 onStartInput 全量复位(2026-10-12 用户定)。
- **未动**:语言仍按旧规则(换框恢复 fieldProfile 默认;`restarting=true` 的**同框重启不动**,不打断手动切的语言)。用户若要求"初始态"也含语言,再把 `restarting` 分支去掉;已实现的层复位与该选择正交。

### 已完成(不再追)

候选词槽内截断(尾补 `…`)、候选栏箭头贴边(10dp)+触摸 40px、五槽统一内缩(分栏仍 5 均分)、上滑改点选、拼音自动分隔符(气泡 + 行内双路径)、拼音气泡 16sp、调出即初始态 —— 均已单测/真机/E2E(16/16)验证并提交。


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
