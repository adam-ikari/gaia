---
id: sensitivity-tiers
title: "三档灵敏度:键盘面那一列改成「死区之外的提交距离」,死区 16dp → 8dp"
category: decision
status: active
tags: [gesture, threshold, sensitivity, deadzone]
created: "2026-10-03T11:25:29"
updated: "2026-10-04T16:49:51"
---

<!-- compiled_truth -->
## 静默窗(按键后不响应滑动):200 → 300ms

`KeyEventGate.DEFAULT_BLOCK_MS`,2026-10-02 用户给的 200,2026-10-04 改成 **300**。

**它不只是「防误触」,还是回弹的一个压制手段。** 按键后候选正在换,
200ms 内划一下可能命中还没稳定的那一页;它与下一次按键的时序叠加就会来回翻。
调大到 300ms 缩小了这个窗口。

⇒ **这一条与阻尼比无关,调 ζ 治不了它。** 遇到「反复回弹」时,
除了弹簧参数,还要查**事件时序**(按键与滑动撞在一起)。

挡掉的手势是**整条都不跟手**(不起拖动、不画位移),不是「跟完再弹回去」——
后者读起来就是延迟,用户会当成卡了再划一次,那时辰就真的在叠了。
这个设计(2026-10-02 定)仍然成立。

## 死区拆成横滑/上滑两个常量

`DEAD_DP_SWIPE = 4f`(横滑翻页)/ `DEAD_DP = 8f`(上滑飞字)。

2026-10-02 用户要求横竖统一,2026-04 反过来要求「翻页更灵 + 上滑不动」——
**共用一个常量时这两条是冲突的**,所以拆开。翻页手指总行程 24px → 16px。

**代价**:这一带出过「死区被减两次」的 bug(`pageDragDeadPx` 在键盘面驱动时
必须返回 **0** 而不是本常量,因为基线定在「死区被跨过的那一帧」)。
所以新增常量后**每个消费者都要单独确认量纲**,不能以为「统一了常量」就等于
「统一了口径」。


## Timeline

- time: 2026-10-03T11:25:29
  kind: decision
  summary: "Created this page: 三档灵敏度:键盘面那一列改成「死区之外的提交距离」,死区 16dp → 8dp"
  source: created via brain create-page
  affects: [sensitivity-tiers]

- time: 2026-10-03T11:25:40
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-03 v0.7.0 手感重设"
  affects: [sensitivity-tiers]

- time: 2026-10-04T16:49:51
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-04 静默窗 200→300ms:它压的是回弹,不是手感"
  affects: [sensitivity-tiers]
