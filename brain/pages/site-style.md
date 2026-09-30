---
id: site-style
title: "站点视觉:Apple 风格"
category: decision
status: active
tags: [design, theme]
created: "2026-09-30T12:15:33"
updated: "2026-09-30T12:16:40"
---

<!-- compiled_truth -->
站点视觉按 **Apple 官网那种语言**做,不是"深色科技风"。具体落法(改 `docs/.vitepress/styles/apple.css`,
由 `theme/index.ts` 引入):

- **字体栈**:`-apple-system, "SF Pro Text", "SF Pro Display", "PingFang SC", "Helvetica Neue",
  "Microsoft YaHei", sans-serif` —— 非 Apple 设备上退回 PingFang/雅黑,气质不会垮。
- **色板**:浅色 `#fff` 底 + `#f5f5f7` 分段底色,正文 `#1d1d1f`,次要文字 `#6e6e73`,
  主色 `#0071e3`;深色模式纯黑底、`#f5f5f7` 正文、`#1d1d1f` 卡片底。分隔线一律低透明度发丝线。
- **排版**:hero 标题 `clamp()` 到 40–64px、字重 600、字距收紧到 -0.02em 左右;正文行高 1.8,
  正文栏宽约 750px。Apple 的辨识度主要来自**大标题 + 紧字距 + 大量留白**,不是圆角。
- **导航**:`position: sticky` + `backdrop-filter: saturate(180%) blur(20px)` + 半透明底。
- **组件**:按钮胶囊形(`border-radius: 980px`);特性卡片 18px 圆角、悬停轻微上浮;不加厚边框。
- **节奏**:段落间距 96–120px,分区之间用 `#f5f5f7` 交替。

**验证限制要记住**:这台机器当时没有连浏览器工具,视觉只能靠构建产物里的 HTML/类名核对,
不能真看渲染效果。所以改动要保守 —— 用 VitePress 已有的 CSS 变量和类名做覆盖,别去魔改
组件结构;改完至少 `npm run build` 过一遍,并把"视觉未肉眼验证"这件事如实告诉用户。


## Timeline

- time: 2026-09-30T12:15:33
  kind: decision
  summary: "Created this page: 站点视觉:Apple 风格"
  source: "2026-09-30 用户定"
  affects: [site-style]

- time: 2026-09-30T12:16:40
  kind: decision
  summary: "Apple 风格:大字重标题紧字距、#f5f5f7 分段底色、半透明毛玻璃导航、胶囊按钮、大留白"
  source: "2026-09-30 用户定:采用 Apple 的网站风格"
  affects: [site-style]
