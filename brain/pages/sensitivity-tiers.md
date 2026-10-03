---
id: sensitivity-tiers
title: "三档灵敏度:键盘面那一列改成「死区之外的提交距离」,死区 16dp → 8dp"
category: decision
status: active
tags: [gesture, threshold, sensitivity, deadzone]
created: "2026-10-03T11:25:29"
updated: "2026-10-03T11:25:40"
---

<!-- compiled_truth -->
## 三档的取值,以及那一列到底是什么

| 档 | 键盘面(死区外提交距离) | 候选栏翻页 |
| --- | --- | --- |
| sensitive | 0.16 | 4 |
| **normal**(默认) | 0.22 | 8 |
| conservative | 0.33 | 20 |

**键盘面那一列曾经含死区**,所以同一个数字在手感上偏钝。改成**只算死区之外
的距离** —— 于是手感允许变化,不用迁就旧数字。

`PAGE_COMMIT_FRAC` 与 `L5_COMMIT_FRAC` 都定在 **0.22**,与 `DEFAULT` 对齐。

`DEFAULT = "normal"`,`prefs.xml` 的 `defaultValue` 也是 normal。
关键在于:**把用户已经认可的那个手感,搬到了「normal」这个名字上**,而不是
发明第四档或者改动三档的含义。这样旧用户升级后手感不变,而档位语义第一次
说得通(以前 sensitive / normal / conservative 与实际松紧是对不上的)。

## 死区 16dp → 8dp

Titan 2 是 1440×1440 @320dpi(density 2.0),8dp = 16px。
死区缩小是「跟手」的一部分,不是独立的参数调整 —— 死区越大,轻扫越像没动。

## 死区必须只减一次

[[spring-damping-direction]] 里那条 `Fling`/`Landing` 漂移的问题,
在死区上也发生过一次:候选栏那一路减了死区,`FlyDetector` 的兜底路径忘了减。
两处减 ⇒ 实际死区是两倍。**统一到 `Landing` 之后这类分叉才从结构上消失。**

`Fling` 现在只剩 `DECELERATE` 衰减与 `SpeedTracker`,不再自己算落点。


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
