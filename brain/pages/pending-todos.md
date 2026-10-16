---
id: pending-todos
title: "待办清单(2026-10-12 收尾时点)"
category: project
status: active
tags: [todo, backlog]
created: "2026-10-12T16:55:18"
updated: "2026-10-16T03:20:06"
---

<!-- compiled_truth -->
## 待办(2026-10-16 更新,按价值排序)

### 0. L6 虚拟键盘 / 剪贴板面板 / 三态开关:视图真机冒烟待补

- **已过**:状态机 JVM 单测(`L6KeyboardTest` / `ClipHistoryTest` / `ImeControllerTest` 的连续消费用例)
  全绿;`assembleDebug` + `gate.sh`(构建/R8/JNI)过;布局/类/drawable 都在产物里。
- **待做(本机无真机、无 `/dev/kvm`,只能真机跑)**:
  - L6:空闲态开合面板、点面板键上屏、上下滑翻页、切 tab、Esc(Sym+Q)关面板。
  - **剪贴板 tab(2026-10-16)**:切到剪贴板 tab;点条目上屏(含**纯字母内容不进组词**);
    敏感内容遮罩 `••••`;长文本截断;空态占位;复制新内容后面板**原地刷新、不弹回第 0 个 tab**。
  - **三态开关(2026-10-16)**:btn_l5 **常驻**、点击 OFF→L5→L6→OFF 循环;图标随态切换
    (展开箭头 / 键盘 / 收起箭头);`Sym+Space` 同效;默认 OFF;组词/换框/切语言/Esc 收面板。
- **E2E 场景 `l6-keyboard` 已写好**(`tools/aitest/scenarios.mjs`),**尚未跑过** ——
  剪贴板 / 三态**尚无 E2E 场景**。
- **设计见** [[l6-virtual-keyboard]] / [[clipboard-panel]]。

### 1. `rawAfterConsuming` 撇号坐标:文档与实现矛盾,raw 含 `'` 时可能截错缓冲

- **事实**:`RimeEngine.query` 重建时**跳过 `'` 不喂引擎**(代码);而 `RimeEngine.rawAfterConsuming` 的注释、`ImeController.readRemainder` 的注释(「引擎坐标含 '」)都说撇在喂进去的串里,依据是宿主机探针(绕过 skip 直接喂)+ E2E(`nihao` 无撇,两种坐标语义巧合一致)。
- **风险**:用户若能输入 `'`,选词后剩余缓冲可能被截错位。**注意 2026-10-15 起 `'` 有了稳定入口**(拼音中长按 L 插分节符,不可关闭),所以这条从"几乎不可达"升级为"可达且会真被用到的路径" —— 优先级上调。
- **修法方向**:实现改成数非撇字符的映射(`ImeController.displaySpelling` 已按此实现,可对齐),并把三处注释改成同一个口径;先在真机用长按 L 造出含撇 raw,再验实际消费值(教训见 [[unverified-as-fact]]:探针证据不等于生产证据)。

### 2. `five-key-slots` E2E 断言脆弱:三次查询候选序随词频漂移

- **现象**:断言要求 Shift/Alt/Ctrl 三键上屏**互异的词**;引擎候选序在查询间重排。
- **证据**:select 埋点显示三键 zone 全部正确(0/4/3),Ctrl 选的就是它槽位当时显示的词 —— 功能无 bug,是断言假设了候选序不变。
- **修法方向**:把断言改成**不变量**——从 select 日志解析每键的 zone(Shift=0/Sym=1/空格=2/Ctrl=3/Alt=4)与 consumed 映射,不再比词面。

### 已完成(不再追)

候选词槽内截断、候选栏箭头贴边+触摸 40px、五槽统一内缩(分栏仍 5 均分)、上滑改点选、拼音自动分隔符(翻页跟随 + 每段边界补撇)、拼音气泡 16sp、调出即初始态(状态/拼音区/候选词清空;邮箱框默认英文压过 restarting)、评审修复(spelling 随组词一起清)、librime 两次更新(f6ea8d94 冒烟过 / 0d00afba 设备冒烟随当天 E2E 一并还清)、dev 发布(含站点)、**长按字母键上屏 L2 符号 + 拼音中长按 L 插分节符**(2026-10-15,四态真机 + E2E 16/16 + 9 条单测)、**连续消费丢候选**(2026-10-16,consumed 绝对坐标 → rawBase 折算;探针 chain 实测 + 单测)、**L6 剪贴板 tab + 三态开关**(2026-10-16,单测 + gate 过)。


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
  source: "2026-10-14 收尾"
  affects: [pending-todos]

- time: 2026-10-15T13:41:16
  kind: decision
  summary: "L6 虚拟键盘定案(同一次对话问定):形态=条上方浮层;内容=通用容器+出厂页;开启=仅空闲态,再按或 Esc 关;翻页=tab 内上下翻。视图还有一层设备冒烟欠账待真机还,见本页第 0 项"
  source: "2026-10-15 L6 虚拟键盘"
  affects: [pending-todos, l6-virtual-keyboard]

- time: 2026-10-15T13:52:16
  kind: decision
  summary: "2026-10-15 长按 L2 符号 + 拼音中长按 L 插分节符已完成(四态真机 + E2E 16/16 + 9 条单测),从待办移入已完成"
  source: "2026-10-15 长按 L2 符号定案"
  affects: [pending-todos]

- time: 2026-10-15T13:52:16
  kind: reversal
  summary: "撇号坐标项优先级上调:' 从 2026-10-15 起有稳定入口(长按 L 插分节符,不可关闭),撇号坐标矛盾从'几乎不可达'升级为可达且会被真用到的路径"
  source: "2026-10-15 长按 L2 符号定案"
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

- time: 2026-10-16T03:20:06
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-16 收尾"
  affects: [pending-todos]
