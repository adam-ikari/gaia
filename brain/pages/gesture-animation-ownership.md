---
id: gesture-animation-ownership
title: "手势动画的主人:闩锁不许落在它保证不了的副作用之前,恒假的合取不是闸门"
category: decision
status: active
tags: [animation, gesture, candidate-bar, correctness]
created: "2026-10-28T10:19:50"
updated: "2026-10-28T10:20:22"
---

<!-- compiled_truth -->
# 一句话

候选栏的每一帧画面必须有**一个主人**。主人在场时它归主人画;主人离场时要么自己收尾,
要么有人能认出「这份状态没人带」并回收。两者都没有,就是「粘住显示上一批词」。

## 症状与机制(2026-10-28 用户报「偶尔有始终显示之前候选词的 bug」)

`CandidateBarView.onDraw` 的分支优先级是 fly > pageTurn > rubber > slots。所以 `flyP >= 0f`
而没人收尾时:画的是**冻住的 `flyOutSlots`**,`slots` 被 `setState` 正常更新却永远画不到;
同时 `isFlyAnimating()` 为真 ⇒ `ImeService` 的两态可见性门把候选行**一直撑在屏上**。
打字时候选不动、换输入框也不复位,只有再上滑一次(走 `beginFlyDrag` 里那句无条件的
`cancelFlyDragNow`)才恢复 —— 这就是「偶尔」+「始终」。

进来的路是**键盘表面**那条上滑(候选行自己的触摸那条安全:它先 `startFlyAnim` 再选字):
`FlyDetector` 在越过阈值那一帧就落 `committed = true` 并调 `onFlyCommit` → `commitFly` →
`controller.selectCandidate(zone)`;而动画挂在 `onCandidateSelected` 上,`selectCandidate`
在「这一槽没有词」时两个 `?: return` **什么都不做**。于是没有动画、没有弹簧,探测器抬手时
又因 `if (committed) return null` 不再走 `onFlyAbort` ⇒ `flyP` 永久停在拖动段。
触发条件是具体的:**候选不满 5 个的那一页,在最外侧那一栏上滑**(最后一页、长拼音的短词表)。

## 三条判据

1. **闩锁不许落在它保证不了的副作用之前。** `committed` 承诺「完成段动画在跑」,可它落得
   比动画早,中间还夹着一个能让动画不发生的事实。要么把闩锁推到事实之后,要么让那个事实
   **如实返回**、由落闩锁的一方收尾 —— 本次选后者(`selectCandidate` 返回 Boolean,
   `commitFly` 收到 false 就 `cancelFlyDrag()`)。
2. **在定义上恒假的合取不是闸门,是装饰。** `flyP >= 0f && !isFlyAnimating()` 里
   `isFlyAnimating()` 的第一项就是 `flyP >= 0f`,所以整条永远为假 —— 而这个写法在本文件
   出现**三遍**(横滑接管、同区重入、外部驱动的 MOVE),三条自愈出口一处都没执行过。
   写完判据要问「什么输入能让它成立」,答不出来就是写错了。现在拆成两个语义:
   `isFlyAnimating()` = 「画面归飞字管吗」(可见性用它,拖动段也算),
   `flyDriven` = 「有弹簧在带它吗」(残留判定用它)。
3. **回收残留必须留「谁还在用」的例外。** `setState` 收的是 `flyP >= 0f && !flyDriven &&
   !hasComposition`(判据照搬 `takeOverPageDrag` 里那条翻页残留的清理)。**不能**只看
   `!flyDriven`:手指拖动段本来就没有弹簧,那样等于每帧打断用户正在做的手势。

## 同一族的旁证(为什么这类错反复出现)

飞字收尾历史上靠 `ImeService` 的 `postDelayed(FLY_SETTLE_MS = 260)` **猜时长**,而按键选词的
完成段实测 ~520ms:猜早了动画半路没画布,猜晚了候选行多撑一截。两条是同一个错 ——
**用时间或承诺,代替「现在有没有人在推进这个状态」这个事实**。2026-10-02 起动画自己报完成
(`onFlyFinished`),这次补上的是剩下那半个:它承诺了,但那条路上没人跑。

## 还没验证的(别把这段当测过)

- **没有真机复现过**。以上是从代码读出的因果链,加两个可证伪预言:粘住时敲字候选栏一字
  不变(而输入框拼音在变);换一个输入框也不复位。
- **E2E 场景没写**。`tools/aitest/scenarios.mjs` 有 `device.swipe`,但 2026-10-28 手头没设备;
  坐标与抬手窗没实测就写场景,等于给仓库添一条会假过的断言。插上 Titan 2 之后该钉的是:
  半满那一页的最外侧空区上滑 ⇒ 候选栏回落到当前页的词,不显示旧词。
- 单测只覆盖 controller 那半边(`ImeControllerTest.半满那一页的空槽要如实报选不中`);
  视图那半边没有 Robolectric,测不到。


## Timeline

- time: 2026-10-28T10:19:50
  kind: decision
  summary: "Created this page: 手势动画的主人:闩锁不许落在它保证不了的副作用之前,恒假的合取不是闸门"
  source: "2026-10-28 修「偶尔始终显示之前候选词」时定案"
  affects: [gesture-animation-ownership]

- time: 2026-10-28T10:20:22
  kind: decision
  summary: "候选栏每一帧必须有一个主人:闩锁不许落在它保证不了的副作用之前;恒假的合取是装饰不是闸门;回收残留要留「手指还在拖」的例外"
  source: "2026-10-28 修「偶尔始终显示之前候选词」定案(未真机复现)"
  affects: [gesture-animation-ownership]
