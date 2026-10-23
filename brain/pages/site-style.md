---
id: site-style
title: "站点视觉"
category: decision
status: active
tags: [Apple 风格:design, theme]
created: "2026-10-08T01:04:00"
updated: "2026-10-23T13:21:42"
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
- **命中区域**:触控设备上可点元素不低于 44px(按钮、导航项、转化动作);
  视觉宽度该窄的(如 ¶ 锚点)用伪元素扩命中区,不改布局。详见 [[site-mobile]]。

**验证手段(2026-10-01 更新,原先的限制已解除)**:这台机器没有连上桌面浏览器工具,
但**可以**用本机 `/usr/bin/chromium-browser` + `puppeteer-core` 无头实测 ——
能看真实渲染截图,也能量投影后的几何(`getBoundingClientRect`)和命中区域。
版式改动因此可以做到"量过再改",不必只靠类名推断。具体做法与判据见 [[site-mobile]]。
改动仍要保守:优先用 VitePress 已有的 CSS 变量和类名做覆盖,别魔改组件结构;
改完跑 `npm run build`,并把**没有真机验证**这件事如实告诉用户
(无头 Chromium 只覆盖桌面渲染,Titan 2 是按视口模拟的,不是那台机器)。


## Timeline

- time: 2026-10-08T01:04:00
  kind: decision
  summary: "Created this page: 站点视觉"
  source: "2026-10-08 站点源码并入本仓库,页面随之迁移"
  affects: [site-style]

- time: 2026-10-08T01:04:05
  kind: decision
  summary: "从公开仓 adam-ikari/gaia 迁入:站点源码与页面知识一并搬进本仓库 web/"
  source: "2026-10-08 monorepo 化"
  affects: [site-style]

- time: 2026-10-08T01:04:11
  kind: decision
  summary: "\"Created this page: 站点视觉:Apple 风格\""
  source: "\"2026-09-30 用户定\""
  affects: [site-style]

- time: 2026-10-08T01:04:11
  kind: decision
  summary: "\"Apple 风格:大字重标题紧字距、#f5f5f7 分段底色、半透明毛玻璃导航、胶囊按钮、大留白\""
  source: "\"2026-09-30 用户定:采用 Apple 的网站风格\""
  affects: [site-style]

- time: 2026-10-08T01:04:11
  kind: decision
  summary: "\"视觉验证限制已解除:浏览器工具当时未连接,现在改用本机 /usr/bin/chromium-browser + puppeteer-core 无头实测(35 组视口),能看真实渲染与投影几何。见 [[site-mobile]]\""
  source: "\"2026-10-01 移动端修复\""
  affects: [site-style, site-mobile]

- time: 2026-10-08T01:04:11
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [site-style]

- time: 2026-10-23T12:51:40
  kind: decision
  summary: "下载区移入 hero:两条渠道(β 主 / dev 可选)的按钮与校验值直接放在标题与 tagline 下方;原「下载。」分区与 frontmatter 单按钮移除。≤430px 时两按钮堆叠成整行。"
  source: "2026-10-23 用户要求「下载放到 hero 区域」"
  affects: [site-style, site-mobile]

- time: 2026-10-23T13:21:42
  kind: decision
  summary: "下载按钮显示 APK 的完整 versionName(0.7.11-beta.<hash>),不再只有 hash;发布脚本从下载到的包 manifest 读出并在构建期注入,测试版读不出就拒绝发布"
  source: "2026-10-23 用户要求「beta版本要显示版本号的」"
  affects: [site-style, site-copy, release-flow]
