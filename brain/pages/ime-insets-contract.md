---
id: ime-insets-contract
title: "IME 窗口 insets 契约:窗口全屏不等于内容全屏(Termux 空白根因)"
category: decision
status: active
tags: [ime android termux]
created: "2026-09-27T22:06:57"
updated: "2026-09-27T22:06:57"
---

<!-- compiled_truth -->
输入法窗口的 insets 契约：**窗口全屏 ≠ 内容全屏**。默认 `onComputeInsets` 把 `contentTopInsets` 取成根视图顶；组词态根视图被 `ImeRootView.flyMode` 拉到全屏后该值 = 0，背后 App 就认为输入法内容占满全屏，把自己的内容区压成 0——**Termux 表现为终端整片空白 + 主动 `hideSoftInput`（状态条/候选条一起消失）**。

修法（`ImeService.onComputeInsets` 覆写）：
- `contentTopInsets` / `visibleTopInsets` = 状态行或候选行的 `getLocationInWindow()[1]`。组词态与空闲态都等于"窗口底往上 36dp"，App 侧既不 resize 也不 pan。
- `touchableInsets = TOUCHABLE_INSETS_FRAME`：触摸仍用整个窗口框架。若沿用默认 `TOUCHABLE_INSETS_VISIBLE`，visibleTop 一改到行顶，条上方区域就收不到键盘面飞字手势。
- 窗口背景必须显式透明（`Theme.InputMethod` 的 windowBackground 不透明会把背后 App 整片盖住）：`onCreate` 里 `window.window.setBackgroundDrawable(ColorDrawable(TRANSPARENT))`。

取证要点：`dumpsys window windows` 看 InputMethod 的 `mGivenContentInsets`（应为 `[0,1296][0,0]` 即窗口顶往上 1296px）；`logcat | grep ImeTracker` 看 App 是否还在发 `onRequestHide ... HIDE_SOFT_INPUT`（修复后应消失）。

观察到的旁现象：`adb install -r` 之后 IMMS 不会自动绑到本输入法（`mBoundToMethod=false`，Termux 的 show 请求仍记 `onShown`），先启动一次本 app 的 Activity 再回前台、之后切换键盘按钮即可恢复。


## Timeline

- time: 2026-09-27T22:06:57
  kind: decision
  summary: "Created this page: IME 窗口 insets 契约:窗口全屏不等于内容全屏(Termux 空白根因)"
  source: created via brain create-page
  affects: [ime-insets-contract]

- time: 2026-09-27T22:06:57
  kind: decision
  summary: "contentTopInsets 必须上报 36dp 条顶而非全屏根视图顶,否则背后 App 内容区被压成 0(Termux 空白并主动收输入法);touchable 保持 FRAME 保飞字;窗口背景必须透明"
  source: brain update-truth
  affects: [ime-insets-contract]
