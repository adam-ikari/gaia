---
id: spring-damping-direction
title: "阻尼方向:确认动作要欠阻尼,回弹要过阻尼;速度只进一次"
category: decision
status: active
tags: [animation, physics, spring, damping]
created: "2026-10-03T11:23:42"
updated: "2026-10-09T08:52:52"
---

<!-- compiled_truth -->
## 一、ζ 从 0.72→0.9→0.95→1.0 四轮「全白费」的真正原因(2026-10-09 推翻)

**原结论是「问题在拖动阶段的镜像跳变,ζ 与此无关」。那个结论是在错误前提下得出的。**

真正的原因更靠底:**spec 里写的 ζ 从来没有送到引擎。**

`SpringBridge.toOfficial()` 写成:

```kotlin
SpringForce(mass).apply {
    setStiffness(this@toOfficial.stiffness)   // 有限定,对
    setDampingRatio(dampingRatio)             // 没限定,错
}
```

裸名字 `dampingRatio` 在 `apply` 作用域里解析到 **receiver 自己**
(`SpringForce.getDampingRatio()`)—— 即刚 new 出来的默认值。androidx
`dynamicanimation:1.1.0` 的 `SpringForce()` 把 `mDampingRatio` 硬编码成 **0.5**
(字节码:`ldc2_w 0.5d` → `putfield mDampingRatio`)。

⇒ **全项目的弹簧(翻页 / 橡皮筋 / 飞字 / 键帽)实际都跑 ζ=0.5。**
spec 写 0.72 还是 0.95 还是 1.0,结果一模一样。

### 真机证据

整页 1360px 补完,spec 写 ζ=1.0:

| 量 | spec 写的 | 引擎手上(`getDampingRatio()` 回读) | 逐帧轨迹反解 |
|---|---|---|---|
| stiffness | 109.66 (=ω₀²) | 109.66 ✓ | ω₀=10.58 → 响应 0.594s ✓ |
| dampingRatio | **1.0** | **0.5** | **ζ=0.50** |

逐帧轨迹:峰值 1581.6 @350ms(target 1360,过冲 **221.6px = 16.3%**),
随后 1356.9 / 1360.99,穿越间隔 349ms。
由 t_peak 与过冲量反解 α=ζω₀=5.29、ω_d=9.16 ⇒ ω₀=10.58、**ζ=0.500**。

同一组参数下临界阻尼解 `(A+Bt)e^(-ωt)` **只有一个零点 ⇒ 过冲恒为 0**,
实测 max|x_actual − x_critical| = 439.8px —— 不可能只是噪声。

修好后同场景:`ring:` **一条都没有**(零穿越),`SpringChannel` 起跑行
`engZ=1.0`,`page-interrupt` 场景转绿,真机 E2E 13/13。

### 为什么四轮调参全都无效

| ζ(spec 写的) | 引擎实际 | 1440px 上的过冲(当时**以为**) | 真实过冲 |
|---|---|---|---|
| 0.72 | 0.5 | 55.3px | 237px |
| 0.90 | 0.5 | 2.2px | 237px |
| 0.95 | 0.5 | 0.1px | 237px |
| 1.0 | 0.5 | 0px | 237px |

那一串「55.3 → 2.2 → 0.1」是**用 spec 的 ζ 在纯函数里算的**,
`PageSettleTest.提交的过冲严格为零` 也是同一个纯函数 —— 它永远绿,因为
它检查的是「spec 声称的值」而不是「引擎收到的值」。

## 二、这类错的识别特征(比结论更值钱)

1. **一个参数对一个参数错。** 刚度那行当时写了限定符,所以刚度是对的。
   两件事混在一起,现象就成了「响应时间正常,只是总有点过冲」——
   像一个手感参数问题,而不是一个接线问题。
2. **历史结论的精度可疑地整齐。** 2.2px → 0.1px 这种数量级,
   而真机上从来没量到过那些数。纯函数算出来的数字天然「好看」,
   因为它用的是你以为的量。
3. **判据指向了相邻量。** `ring:` 只能告诉你穿越了几次,
   要反解 ζ 得拿**逐帧** `(value, velocity)` 去拟合。
   同样,埋点里只打 `spec.ζ` 是不够的 —— **必须打从引擎回读的值。**

## 三、修法(不是加限定符)

加 `this@toOfficial.` 能修好这一处,但同样的错随时会出现在下一个参数上,
而且它**编译得过、单测看不见**。所以:

1. 换算拆成**纯函数** `SpringSpec.forces(): SpringForces` ⇒ 可 JVM 单测
   (`SpringForcesTest` 会断言阻尼比**不是 0.5**)。
2. `toOfficial()` **不用 `apply`**,先把两个数算进局部变量,再逐个 setter
   ⇒ 「名字解析到 receiver」这一类错**在结构上不可能发生**。
3. `SpringChannel` 起跑 / 打断的埋点带 `engK`/`engZ`(从引擎 `getSpring()` 回读),
   作为**常驻对账护栏**:哪天翻译层又错了,logcat 里立刻对不上账。

## 四、尚未重新验证的部分(诚实标注)

- 「拖动阶段的镜像跳变导致不跟手」这个结论是在 ζ 一直是 0.5 的前提下得出的。
  镜像跳变是**拖动结构**问题,与阻尼无关,所以修法大概仍成立;
  但**「ζ 调多少都改不了」这句话是错的**,不该再当依据。
- 「用户报的多次回弹来自 `KeyEventGate` 静默窗与换页时序,而非 ζ」——
  同样是在错误前提下得出的,需要重新评估:ζ=0.5 本身就是「一次过冲 + 一次回摆」
  的典型形状,足够解释用户看到的现象。

## 五、仍然成立的部分

- **确认动作要过一点阻尼、回弹要过阻尼**:语义判断不变,但现在必须
  **先确认参数真的到了引擎**,再谈调多少。
- **速度只进一次**:惯性只决定落点(`Landing`)、不给弹簧初速。这条与本次 bug 无关。


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

- time: 2026-10-09T08:52:44
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-09 真机:toOfficial() 的 dampingRatio 解析到 SpringForce 自己的 getter,引擎实际跑 androidx 默认的 0.5,spec 写的 ζ 从未生效"
  affects: [spring-damping-direction]

- time: 2026-10-09T08:52:52
  kind: reversal
  summary: "推翻:0.72→0.9→0.95→1.0 四轮调参无效的原因不是「问题在拖动阶段」,而是 spec 里的 ζ 从未送到引擎(toOfficial 的裸 dampingRatio 解析到 SpringForce.getDampingRatio() = androidx 默认 0.5);同时标注「ζ 调多少都改不了」这句话是错的"
  source: "2026-10-09 真机逐帧轨迹拟合 + getDampingRatio() 回读,commit c928fb8"
  affects: [spring-damping-direction, unverified-as-fact, paging-feel]
