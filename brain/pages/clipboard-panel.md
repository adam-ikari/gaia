---
id: clipboard-panel
title: "剪贴板历史面板:复用 L6 浮层的第 4 个 tab(每条一行 / 敏感遮罩 / 点即粘贴)"
category: decision
status: active
tags: [ime, clipboard, l6, ui]
created: "2026-10-16T03:19:16"
updated: "2026-10-17T05:16:49"
---

<!-- compiled_truth -->
## 剪贴板历史面板(2026-10-16 定案,2026-10-17 补读取防御)

复用 [[l6-virtual-keyboard]] 浮层做第 4 个 tab(`clip`),不新建面板。

- **每条一行**:`L6Layout.ROWS`(整行宽),与出厂符号盘的 `GRID`(8 列等宽)并存 —— 键格排布是 tab 的属性,不是面板的。
- **敏感遮罩**:密码管理器设了 `EXTRA_IS_SENSITIVE`(Android 13+)的条目显示 `••••`,但**点它仍上屏全文**(遮罩不是禁用)。
- **长文本截断**:超 24 字符显示 `…`,上屏仍是全文。
- **点即粘贴**:走独立入口 `ImeController.commitClipboardText()` → 直接 `host.commitText`,**不复用** `commitL4Key` —— 后者会把纯字母当拼音进组词缓冲(L6 定案「面板无 a-z」的前提对剪贴板不成立)。上屏后面板留原地。
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
