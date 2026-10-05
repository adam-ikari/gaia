---
id: spring-damping-direction
title: "阻尼方向:确认动作要欠阻尼,回弹要过阻尼;速度只进一次"
category: decision
status: active
tags: [animation, physics, spring, damping]
created: "2026-10-03T11:23:42"
updated: "2026-10-05T00:59:57"
---

<!-- compiled_truth -->
## 「过冲」先怀疑拖动阶段,不要先怀疑弹簧(2026-10-04 v0.7.9)

用户报「候選詞欄滑動還是過沖」时 PAGE_COMMIT 已是 ζ=1.0。
**先证明弹簧无罪**,再去别处找 —— 否则会在参数上白调好几轮。

### 排除弹簧的判据(可复算)

临界阻尼 `x−target = (A+Bt)e^(−ω₀t)`,`B = v0 + ω₀A`;过冲门槛
`r* = |v0|/(ω₀·|A|)` = 10.6(resp 0.60) ~ 22.6(resp 0.28) @ ζ=1.0。
**从静止起步的弹簧飞行中 r 最大只有 1/e = 0.368** ⇒ 即使被
`animateToFinalPosition` 打断保留速度,也过不了冲。

⇒ 「零过冲」这个保证在**从静止起步**时是数学事实;只要引入初速度就要重算门槛。

### 真根因:每帧重判方向 + 无条件镜像

`onTouchEvent` 的 MOVE 分支**每个事件**都调 `takeOverPageDrag`(门槛仅 4dp),
而 `dir` 按 `event.x - downX` 算。`beginPageDrag` 的 `dir != pageDir` 分支
做 `pageSlide = full - pageSlide`。手指在按下点附近抖 ±5px:

    5px → 镜像 → full−5 = 1435 → 再抖 → 5

抖动期间 `pageSlide` 永远很小 ⇒ 每次镜像都是接近整页的跳变。
**发生在弹簧起跑之前,所以 ζ 怎么调都没用。**

### 修法:镜像闩锁(`allowPageMirror`)

两个条件缺一不可:**过了提交距离**才镜像(否则视觉代价最大而语义为零,
让 `DragGesture.slide` 自然带回 0 就好)+ **一次手势只镜像一次**
(镜像后位移变大,再反向又满足前一条,会来回)。闩锁在起手/收尾复位。

### 教训

「动画参数调到 X 仍不改善」⇒ **先证伪参数这条路径**(用解析解或数值积分
给出「不可能」的判据),再去找结构性原因。ζ 从 0.72→0.9→0.95→1.0 四轮
全白调,是因为问题在拖动阶段。


## Timeline

- time: 2026-10-03T11:23:42
  kind: decision
  summary: "Created this page: 阻尼方向:确认动作要欠阻尼,回弹要过阻尼;速度只进一次"
  source: created via brain create-page
  affects: [spring-damping-direction]

- time: 2026-10-03T11:24:06
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-03 v0.7.0 动画重做:用户连报三条症状后定位"
  affects: [spring-damping-direction]

- time: 2026-10-04T04:30:21
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-04 修翻页收尾落点 + 提交/收回分两支弹簧"
  affects: [spring-damping-direction]

- time: 2026-10-04T06:15:36
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-04 ζ 0.72→0.9:过冲比例 × 行程 = 隐性参数被行程放大"
  affects: [spring-damping-direction]

- time: 2026-10-04T16:50:00
  kind: reversal
  summary: "提交 ζ 0.72→0.9→0.95→1.0:用户明确「不要过衝也不要回彈」,多次回弹在数学上不可能来自欠阻尼"
  source: "2026-10-04 用户「非線性動畫到翻頁到位不要过衝也不要回彈」"
  affects: [spring-damping-direction]

- time: 2026-10-05T00:25:53
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-04 读 SpringBridge.settle 与临界阻尼解析解:ζ=1.0 只在从静止起步时保证零过冲"
  affects: [spring-damping-direction]

- time: 2026-10-05T00:59:57
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-04 v0.7.9:ζ=1.0 仍过冲,根因是拖动阶段的方向镜像,与阻尼比无关"
  affects: [spring-damping-direction]
