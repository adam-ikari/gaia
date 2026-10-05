---
id: spring-damping-direction
title: "阻尼方向:确认动作要欠阻尼,回弹要过阻尼;速度只进一次"
category: decision
status: active
tags: [animation, physics, spring, damping]
created: "2026-10-03T11:23:42"
updated: "2026-10-05T00:25:53"
---

<!-- compiled_truth -->
## ζ=1.0 只在**从静止起步**时才保证零过冲(2026-10-04)

用户要求「非線性動畫、到位不要过衝也不要回彈」⇒ 提交改成临界阻尼 ζ=1.0。
但用户仍报「候選詞欄滑動還是过衝」。**根因不在阻尼比,在初速度。**

临界阻尼的解是 `x(t)-target = (A + B t)·e^(-ω₀ t)`,`B = v0 + ω₀·A`。
归一化 `u(t) = (1 + c t)e^(-ω₀ t)`,`c = ω₀ + v0/A`。

- **v0 = 0** ⇒ `c = ω₀` ⇒ 极值点不在正时间轴 ⇒ **恒不过冲**(response 0.28/0.45/0.60 都是)。
- **趋近速度 v** ⇒ `c = ω₀ - v/A`。`v/A ≤ ω₀` 时仍无过冲;`v/A > ω₀` 时极值为负
  ⇒ **冲过目标再回来**,过冲量 `(s-1)·e^-(1+1/(s-1))·A`,`s = v/(ω₀·A)`。

⇒ **打断的过冲门槛是 `v > ω₀·A`** —— `A` 是**剩余距离**,不是总行程。
所以**越接近目标越容易过冲**(A 小 ⇒ 门槛低),而且 response 越短 ω₀ 越大、越敏感。
实测(response=0.28s 那一档):剩余 100px、速度 8000px/s ⇒ 过冲 63.9px。

⇒ 结论:**「零过冲」这个保证只在「起跑时速度为零」时成立。**
任何「保留在飞速度」的打断路径都会破坏它,与阻尼比无关。

`SpringChannel.settle` 在 `isRunning` 时走 `animation.animateToFinalPosition(target)`,
**刻意保留速度**(注释写明这是速度唯一该被保留的场合)。
那个判断在「连续性」上是对的,与「零过冲」直接冲突 —— 两个目标不能同时要,
要哪个得由用户定。可能的解法:按 `v ≤ ω₀·A` 夹住保留的速度(保住连续性又守住零过冲)。

**待确认**:用户当时测的是哪个版本(反馈与版本错配过一次)。


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
