---
id: spring-damping-direction
title: "阻尼方向:确认动作要欠阻尼,回弹要过阻尼;速度只进一次"
category: decision
status: active
tags: [animation, physics, spring, damping]
created: "2026-10-03T11:23:42"
updated: "2026-10-03T11:24:06"
---

<!-- compiled_truth -->
## 症状与真正的根因

2026-10-03 用户连报三条:「弹性阻尼太强」「翻页成功动画反而不明显」
「没考虑手势滑动的加速度」。看起来是三件事,实际是**两个 bug**,而且第二个
把第一个盖住了。

## 坑一:阻尼**方向**错了 —— 临界阻尼是所有不过冲曲线里最慢的

「弹性太强」的根因不是系数太大,而是**所有弹簧都用同一个方向**。

`androidx.dynamicanimation` 的 `SpringAnimation` 用阻尼比 ζ 描述:

- ζ < 1(欠阻尼):**过冲**,来回振荡 → 有冲量感
- ζ = 1(临界):**最快到达且不过冲**
- ζ > 1(过阻尼):爬行,永远逼近 → 橡皮筋感

关键事实:**临界阻尼是所有不过冲曲线里响应最快的那条**。所以「不过冲」
听起来更「稳」,实际更「肉」。当年为了消掉抖动,几乎所有弹簧都往 ζ ≥ 1 调 ——
于是**确认动作**变成了爬行,用户看到的是「弹性太强」。

现在的取值按**语义**分两类,方向相反:

| 语义 | ζ | 场景 |
| --- | --- | --- |
| 确认 | 0.72 | `DEFAULT` / `FLY_UP` —— 确认动作需要冲量 |
| 回弹 | 0.9 / 1.6 / 1.8 / 2.4 | `SWAP_IN` / `CAP_MOVE` / `FLY_BACK` / `RUBBER_BACK` |

**教训:阻尼不是「调到手滑」,是按「这个动作要什么感觉」选方向。**
看到「弹性太强」先问:是 ζ 太大,还是 ζ 方向错了。

## 坑二:惯性被计了两次 —— 落点算一次,弹簧又给一次初速

手势甩动时,速度进了两个地方:`Landing` 用它算**落点**,`SpringAnimation`
又拿它当**初速度**。于是位移算了两遍速度 ⇒ 冲过头 ⇒ 回弹 ⇒ 「弹性太强」。

修法不是调参,是**让类型上没有双计的机会**:

- `Landing` 负责落点(纯参数与落点,可单测,`Physics.kt`)
- `SpringChannel.settle(from, target)` **故意没有速度参数**
- 打断时用 `animateToFinalPosition`,让弹簧**保留**当前速度

**教训:当两个机制争同一个物理量,在类型上砍掉一个,比在注释里提醒可靠。**

## 坑三:`Fling` 与 `Landing` 是同一模型的两份实现

修完才发现 `Fling` 和 `Landing` 是同一件事写了两遍 —— 而且**两份已经漂移了**:
候选栏那一路减了死区,`FlyDetector` 的兜底路径忘了减。已统一到 `Landing`,
`Fling` 只剩 `DECELERATE` 衰减与 `SpeedTracker`。

**教训:同一个模型的两份实现,漂移只是时间问题。** 见到 `Xxx` 与 `XxxDetector`
并存就假设它们会分叉。

## 落到工程上的位置

- 物理引擎:`androidx.dynamicanimation:1.1.0`(官方 `SpringAnimation`),
  自写的 `Spring.kt` / `SpringValue` 已删
- 桥:`ui/SpringBridge.kt`;纯参数与落点:`ui/Physics.kt`
- **ValueAnimator 零残留**(飞字、候选词替换也都迁到物理引擎)
- L4/L5 键帽动画也走物理引擎;**文字淡出**要注意:画笔 alpha 必须跟着弹簧走,
  写成不带 alpha 的 `fgColor` 常量等于没淡出


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
