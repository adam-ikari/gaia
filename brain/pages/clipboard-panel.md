---
id: clipboard-panel
title: "剪贴板历史面板:复用 L6 浮层的第 4 个 tab(每条一行 / 敏感遮罩 / 点即粘贴)"
category: decision
status: active
tags: [ime, clipboard, l6, ui]
created: "2026-10-16T03:19:16"
updated: "2026-10-20T00:28:50"
---

<!-- compiled_truth -->
## 剪贴板历史面板(2026-10-16 定案,2026-10-17 补读取防御)

复用 [[l6-virtual-keyboard]] 的浮层做 `clip` tab(2026-10-19 合并后是**第 4 个 tab**:
场景/标点/技术/剪贴板),不新建面板。

- **每条一行**:`L5Layout.ROWS`(整行宽),与符号盘的 `GRID`(8 列基础网格,长键跨格)并存
  —— 键格排布是 tab 的属性,不是面板的。**一页两行**(面板固定两行高,见 [[l6-virtual-keyboard]]),
  超过两条翻页。
- **空 tab 隐藏**:没有条目、或条目全被当前输入框的 `allowText` 滤掉时,整个剪贴板 tab 消失
  (2026-10-19 与「没有可显示键的 tab 整个隐藏」同一条规则)。密码框只留可打印 ASCII,
  数字/电话框只留数字与该类型放行字符;过滤后无合法条目就不显示 tab。
- **不参与 L1-L3 单字去重**:L5 字符盘按物理层已能敲出的字去重(2026-10-19 用户
  「L5 不需要 L1-3 已经有的字符」),剪贴板是**整段文本**的快捷入口,不按单字符再筛;
  可粘贴性只由输入框的 `isPanelTextAllowed` 兜底。
- **敏感遮罩**:密码管理器设了 `EXTRA_IS_SENSITIVE`(Android 13+)的条目显示 `••••`,但**点它仍上屏全文**(遮罩不是禁用)。
- **长文本截断**:超 24 字符显示 `…`,上屏仍是全文。
- **点即粘贴**:走独立入口 `ImeController.commitClipboardText()` → 直接 `host.commitText`,**不复用** `commitL4Key` —— 后者会把纯字母当拼音进组词缓冲(面板定案「面板无 a-z」的前提对剪贴板不成立)。上屏后面板留原地。入口另有 `isPanelTextAllowed` 兜底,非法内容不粘贴。
- **ClipHistory 语义**(纯逻辑,`ClipHistoryTest` 7 条):最新在前 / 相邻去重 / 超限淘汰最旧 / 空串忽略 / 默认容量 10。

### 读取防御(2026-10-17)

监听在 `onCreate` 注册、**全程生效**(含 IME 隐藏时别的 App 复制触发回调)。Android 10+ 的后台剪贴板读取限制对当前 IME **多半豁免**(Gboard 同模式不崩),但豁免边界未在真机确证 ⇒ `primaryClip` 读取包 `try/catch (SecurityException) return`,防一次崩进程。


## Timeline

- time: 2026-10-16T03:19:16
  kind: decision
  summary: "Created this page: 剪贴板历史面板:复用 L6 浮层的第 4 个 tab(每条一行 / 敏感遮罩 / 点即粘贴)"
  source: "2026-10-16 用户定案"
  affects: [clipboard-panel]

- time: 2026-10-17T05:16:49
  kind: decision
  summary: "补齐剪贴板面板真实内容(布局/遮罩/粘贴通路/列表语义)+ 2026-10-17 primaryClip 读取防御"
  source: "2026-10-17 代码评审整改"
  affects: [clipboard-panel]

- time: 2026-10-19T19:49:05
  kind: decision
  summary: "2026-10-19 合并后更新:剪贴板 tab 属于 L5 面板(第 5 个 tab),布局类型改称 L5Layout"
  source: "2026-10-19 L5 合并"
  affects: [clipboard-panel]

- time: 2026-10-19T22:43:51
  kind: decision
  summary: "2026-10-19 更新:剪贴板 tab 随合法条目显隐;GRID 统一 8 列基础网格(长键跨格),ROWS 仍整行"
  source: "2026-10-19 L5 网格/空 tab/合法性过滤"
  affects: [clipboard-panel]

- time: 2026-10-19T23:21:23
  kind: decision
  summary: "2026-10-19 更新:剪贴板是 L5 第 4 个 tab(无控制 tab);不参与 L1-L3 单字去重,整段文本仍可粘贴"
  source: "2026-10-19 L5 去重定案"
  affects: [clipboard-panel]

- time: 2026-10-20T00:28:50
  kind: decision
  summary: "2026-10-20 面板固定两行高(见 l6-virtual-keyboard):剪贴板一页由 5 条改为 2 条(CLIP_ROWS_PER_PAGE 撤并到 L5Ui.ROWS_PER_PAGE),超过两条翻页;每条仍一行整行宽、敏感遮罩/截断/点即粘贴不变。"
  source: "2026-10-20 用户「L5的高度固定两行 多的内容需要翻页」"
  affects: [clipboard-panel]
