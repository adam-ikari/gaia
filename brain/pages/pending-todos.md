---
id: pending-todos
title: "待办清单(2026-10-12 收尾时点)"
category: project
status: active
tags: [todo, backlog]
created: "2026-10-12T16:55:18"
updated: "2026-10-16T08:23:01"
---

<!-- compiled_truth -->
## 待办(2026-10-15 更新,按价值排序)

### 1. `rawAfterConsuming` 撇号坐标:文档与实现矛盾,raw 含 `'` 时可能截错缓冲

- **事实**:`RimeEngine.query` 重建时**跳过 `'` 不喂引擎**(代码);而 `RimeEngine.rawAfterConsuming` 的注释、`ImeController.readRemainder` 的注释(「引擎坐标含 '」)都说撇在喂进去的串里,依据是宿主机探针(绕过 skip 直接喂)+ E2E(`nihao` 无撇,两种坐标语义巧合一致)。
- **风险**:用户若能输入 `'`,选词后剩余缓冲可能被截错位。**注意 2026-10-15 起 `'` 有了稳定入口**(拼音中长按 L 插分节符,不可关闭),所以这条从"几乎不可达"升级为"可达且会真被用到的路径" —— 优先级上调。
- **修法方向**:实现改成数非撇字符的映射(`ImeController.displaySpelling` 已按此实现,可对齐),并把三处注释改成同一个口径;先在真机用长按 L 造出含撇 raw,再验实际消费值(教训见 [[unverified-as-fact]]:探针证据不等于生产证据)。

### 2. `five-key-slots` E2E 断言脆弱:三次查询候选序随词频漂移

- **现象**:断言要求 Shift/Alt/Ctrl 三键上屏**互异的词**;引擎候选序在查询间重排。
- **证据**:select 埋点显示三键 zone 全部正确(0/4/3),Ctrl 选的就是它槽位当时显示的词 —— 功能无 bug,是断言假设了候选序不变。
- **修法方向**:把断言改成**不变量**——从 select 日志解析每键的 zone(Shift=0/Sym=1/空格=2/Ctrl=3/Alt=4)与 consumed 映射,不再比词面。

### 已完成(不再追)

候选词槽内截断、候选栏箭头贴边+触摸 40px、五槽统一内缩(分栏仍 5 均分)、上滑改点选、拼音自动分隔符(翻页跟随 + 每段边界补撇)、拼音气泡 16sp、调出即初始态(状态/拼音区/候选词清空;邮箱框默认英文压过 restarting)、评审修复(spelling 随组词一起清)、librime 两次更新(f6ea8d94 冒烟过 / 0d00afba 设备冒烟随当天 E2E 一并还清)、dev 发布(含站点)、**长按字母键上屏 L2 符号 + 拼音中长按 L 插分节符**(2026-10-15,四态真机 + E2E 16/16 + 9 条单测)。


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

- time: 2026-10-15T09:34:15
  kind: decision
  summary: "2026-10-15:第 5 项(调出即初始态语言口径)完成 —— 邮箱框默认英文压过 restarting,真机四态验证;待办剩 3 项"
  source: 2026-10-15
  affects: [pending-todos]

- time: 2026-10-15T13:52:16
  kind: decision
  summary: "2026-10-15:第 1 项(长按 L2 符号上屏)完成 —— 四态真机 + E2E 16/16 + 9 单测;撇号坐标项因长按 L 让 ' 变得可达,优先级上调"
  source: 2026-10-15
  affects: [pending-todos]

- time: 2026-10-16T08:18:38
  kind: decision
  summary: "2026-10-16:长按字母键从「只出 L2」扩成**三选一**(关闭/L2/L3,见 [[ime-interaction-model]]),含一个真机取证过的修正(短按不再闪层状态)+ 长按状态机收口(LongPress 四迁移);单测 331 全绿。状态机整理本身也定了口径:**sealed + 纯函数,不引库**([[state-machine-approach]]);输入机与候选栏手势机按用户决定暂不动。待办仍是撇号坐标 + five-key-slots 两条。"
  source: "2026-10-16 收尾"
  affects: [pending-todos]

- time: 2026-10-16T08:23:01
  kind: evidence
  summary: "2026-10-16 长按三选一(L2/L3)+ 短按不闪修正的回归:E2E 16/16(2 个 AI 判卷场景因未配 AITEST_API_KEY 依例 pending,非失败),无崩溃;单测 331 全绿;真机三态取证(短按不闪 / L2 长按出 ’ 且按住出 alt / L3 长按 T 出《、L 触发 move_end)。"
  source: "2026-10-16 E2E(tools/aitest)+ 真机 TTANCN2100002663"
  affects: [pending-todos]
