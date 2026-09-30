---
id: paging-feel
title: "待修:翻页不跟手、动画太短、缓动要非线性"
category: decision
status: active
tags: [gesture, animation, paging]
created: "2026-09-30T13:23:54"
updated: "2026-09-30T15:57:11"
---

<!-- compiled_truth -->
Titan 2 的屏幕**有两个不同的量**,之前一直被混着用(2026-09-30 用户澄清后分清):

- **物理面板 1440 × 1440**。候选行通栏(两端只露底色不缩进,见 `ime_root.xml`),
  所以**候选行宽 = 1440px,与 dp 设置无关** —— px 是物理的。测试里的 `viewW = 1440f` 指这个。
- **逻辑宽度 598dp**,这是**用户在自己手机上改的最小逻辑宽度**(开发者选项里调的),
  不是面板的固有属性。改它会让实际 density 变成 1440/598 ≈ 2.41(≈385dpi)。

**推论:凡是按 dp 推 px 的注释,密度不能写死 2.5** —— 那是原生 density(1440/576=2.5),
用户改过逻辑宽度之后就不成立了。所以:

- 旧 24dp 判据的像素值,用 **~2.4** 算(约 58px),不写 2.5;
- 布局注释里的屏宽一律写 **598dp**(代码里那个数是对的,是用户实际在用的);
- **不要**把 density 2.5 写进代码注释当事实 —— 那是原生值,会随用户设置变。

站点的响应式断点另算:浏览器 CSS px 跟 Android dp 不是一回事,那条线按 576 做
(见 `hwkbd_ime-web` 的 `responsive.css`),与这里的 dp 无关。

**教训(第二次同源)**:上一轮我把"598dp"当成推错的数改成了 576dp,其实 **598 是对的** ——
错的是我拿"面板 1440 ÷ 假设 2.4 密度"去反推 dp。**dp 宽度不是从 px 推出来的,是用户在
系统里设的**;面板物理尺寸与逻辑宽度之间隔着 density,而 density 会变。


## Timeline

- time: 2026-09-30T13:23:54
  kind: decision
  summary: "Created this page: 待修:翻页不跟手、动画太短、缓动要非线性"
  source: "2026-09-30 用户报障(真机手感)"
  affects: [paging-feel]

- time: 2026-09-30T13:24:27
  kind: decision
  summary: "翻页三项报障的根因与修法:进度被当位移用致 3 倍放大、死区 8% 行宽、settle 线性且太短"
  source: "2026-09-30 用户报障 + 代码核算"
  affects: [paging-feel]

- time: 2026-09-30T14:47:49
  kind: decision
  summary: "三项全部修完(跟手/时长/非线性)+ 惯性物理、橡皮筋、震动开关;记录单测拦下的自身 bug"
  source: "2026-09-30 实现 + 单测结果"
  affects: [paging-feel]

- time: 2026-09-30T15:48:21
  kind: decision
  summary: "Titan 2 参数校正:1440×1440 / density 2.5 / 逻辑宽 576dp,清掉代码里按 598dp×2.4 推的错数"
  source: "2026-09-30 用户给出准确分辨率与逻辑宽度"
  affects: [paging-feel]

- time: 2026-09-30T15:57:11
  kind: decision
  summary: "Titan 2 参数分清两个量:物理 1440×1440(px,通栏宽)vs 逻辑宽 598dp(用户在手机改的最小逻辑宽度)"
  source: "2026-09-30 用户澄清:598dp 是他改的最小逻辑宽度"
  affects: [paging-feel]
