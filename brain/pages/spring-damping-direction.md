---
id: spring-damping-direction
title: "阻尼方向:确认动作要欠阻尼,回弹要过阻尼;速度只进一次"
category: decision
status: active
tags: [animation, physics, spring, damping]
created: "2026-10-03T11:23:42"
updated: "2026-10-04T04:30:21"
---

<!-- compiled_truth -->
## 症状与根因

2026-10-04 用户报三条:「松手时翻不过去」「翻过去/收回的动画不对」
「之前翻頁困難」。

**不是阈值问题,是一个真的 bug。**

`pageSlide` 是 1:1 跟手的(`DragGesture.slide` 只在死区里有一点滑移),
一次翻页完成需要 `pageSlide` 走到 `width` = 1440px。但松手时落点算成了:

```
settlePage(landing.coerceAtLeast(pageSlide))          // 提交
settlePage(Landing.project(pageSlide, 0f, v)...)     // 收回
```

`Landing.project` 答的是「**如果任它惯性滑行,会停在哪**」——
它是**投影**,不是**完成**。`v²/(2a)` 再猛的一甩也只有几百 px,
而过提交线那一帧 `pageSlide` 才十几 px。于是弹簧只走一百多 px
就被 `onFinish` 的 `clearPageTurn()` 截断 —— **整页行程从来没走完,画面是跳过去的**。

实测(Titan 2 屏宽 1440px,提交线 8px):

| pageSlide | v=500 | v=2000 | v=4000 |
| --- | --- | --- | --- |
| 14px(刚过线) | 24 | 181 | 681 |
| 200px | 210 | 367 | 867 |
| 800px | 810 | 967 | **1440** |

只有「已划过 800px + 甩到 4000px/s」这种人手达不到的组合才刚好够走完
⇒ **解释了为什么用户觉得「时好时坏」**。

**整页没走完 ⇒ 视觉上根本不像发生了一次翻页** ⇒「翻頁困難」。

## 修法是**分工**,不是调参

**惯性只决定「翻不翻」(`byFling`),落点只由「走完 / 回原位」决定。**

这与 `SpringChannel.settle` 故意不给速度参数是同一个道理:
**同一个物理量只在一个地方计入**。那一条治的是「双计」,
这一条治的是「用投影当完成」——同一类错误的两种形态。

## 提交与收回分成两支弹簧

之前两者共用 `SpringSpec.DEFAULT`(ζ=0.72),于是**收回时也带一次过冲**。
而收回在语义上是**放弃**(同 `FLY_BACK` 飞字放弃 / `RUBBER_BACK` 撞头),
不该有到位感、更不该来回晃:

| | ζ | response | 语义 |
| --- | --- | --- | --- |
| `PAGE_COMMIT` | 0.72(欠) | 0.26s | 确认动作要冲量 |
| `PAGE_ROLLBACK` | 1.9(过) | 0.24s | 放弃要干脆 |

`SpringChannel.settle` 加了可选 `spec` 参数(默认仍是原来的,既有调用方不变)。
`pageSpring` 一支通道按次换 spec,打断时保留当前 spec。

**为什么收回要比 `RUBBER_BACK`(2.4)稍缓**:橡皮筋是「撞墙」要硬;
收回是「我自己滑回去」,太硬会读成「被弹回来」,像不是自己做的决定。

## 我自己测试里犯的两个错(都记下来)

**其一:过冲公式只对欠阻尼成立。**
常用写法 `exp(-πζ/√(1-ζ²))` 是从欠阻尼解推的,分母 `√(1-ζ²)` 要求 ζ<1。
拿它算 ζ=1.9 直接 domain error。**过阻尼没有过冲** ——
解是纯指数衰减,峰值就是 1。判据要按语义写:欠阻尼 ⇒ 过冲>0,过阻尼 ⇒ 过冲==0。

**其二:百分比阈值是随手拍的。**
我写「短 80% 以上」,实测 `pageSlide=200/v=2000` 是 74.5%,测试红。
这种断言钉住的是**我选的那个数**,换个屏宽就失效。
改成断言「所有现实速度下惯性投影都到不了整页宽度」,并把实测表写进注释。

**教训:断言要钉住语义,不要钉住自己拍的数。**

## 三档提交距离本次没动

标准档死区外只要 8px(屏宽 0.55%),从数字看不可能让人觉得「困难」。
既然 bug 已经解释了那个症状,就先修 bug,手感等真机复测再定 ——
不要在没验证根因被消除之前就去调参数。


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
